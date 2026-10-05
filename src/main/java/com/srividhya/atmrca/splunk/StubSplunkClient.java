package com.srividhya.atmrca.splunk;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Stand-in for Splunk on a local run: one synthetic day of ATM failure events, the same day
 * every time (fixed seed) but always ending "now". Each event is raw JSON in the shape the
 * container platform writes - @timestamp, hostname, kubernetes{container_name, namespace_name,
 * pod_name}, level, message, openshift.labels - with the application log line, its tracing
 * metadata and stack trace inside "message". All names and data are made up; card numbers are
 * the published test numbers.
 */
@Component
@ConditionalOnProperty(name = "rca.splunk.mode", havingValue = "stub", matchIfMissing = true)
public class StubSplunkClient implements SplunkClient {

    private static final long SEED = 42;
    private static final String NAMESPACE = "atm-prod";
    private static final String[] ATM_IDS = { "0009L", "0100K", "4276I", "0231M", "1187C", "0460T" };
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss,SSSxxx");

    /**
     * @param text builds the log text; null exception means a UI event with no Java exception
     * @param fromFrac toFrac the part of the day (0-1) in which the failure occurs
     */
    private record Failure(String component, String logger, String level, String exception,
            Function<Random, String> text, String stack, int count, double fromFrac, double toFrac) {
    }

    private static final List<Failure> FAILURES = List.of(
            new Failure("app-atm-withdrawal-prod1", "com.example.bank.withdrawal.host.HostAuthClient", "ERROR",
                    "com.example.bank.withdrawal.host.HostAuthTimeoutException",
                    r -> "host authorization timed out after 500ms (attempt 2/2)", """
                            \tat com.example.bank.withdrawal.host.HostAuthClient.authorize(HostAuthClient.java:58)
                            \tat com.example.bank.withdrawal.WithdrawalService.withdraw(WithdrawalService.java:41)
                            \tat org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:885)
                            Caused by: java.net.SocketTimeoutException: Read timed out
                            \tat com.example.bank.withdrawal.host.HostAuthClient.call(HostAuthClient.java:73)""",
                    64, 0.55, 0.65),
            new Failure("app-atm-withdrawal-prod1", "com.example.bank.withdrawal.cash.CassettePlanner", "ERROR",
                    "com.example.bank.withdrawal.cash.InsufficientCassetteException",
                    r -> "cassette " + (1 + r.nextInt(4)) + " cannot dispense amount=" + (20 * (1 + r.nextInt(20))), """
                            \tat com.example.bank.withdrawal.cash.CassettePlanner.plan(CassettePlanner.java:66)
                            \tat com.example.bank.withdrawal.WithdrawalService.withdraw(WithdrawalService.java:47)""",
                    11, 0, 1),
            new Failure("app-atm-deposit-prod1", "com.example.bank.deposit.validation.DepositValidator", "ERROR",
                    "java.lang.NullPointerException",
                    r -> "Cannot invoke \"com.example.bank.deposit.model.Envelope.getCurrency()\" because \"envelope\" is null", """
                            \tat com.example.bank.deposit.validation.DepositValidator.validate(DepositValidator.java:35)
                            \tat com.example.bank.deposit.DepositService.deposit(DepositService.java:29)""",
                    22, 0, 1),
            new Failure("app-atm-deposit-prod1", "com.example.bank.deposit.ledger.LedgerClient", "ERROR",
                    "com.example.bank.deposit.ledger.LedgerPostingException",
                    r -> "ledger posting failed status=503 attempt=" + (1 + r.nextInt(3)) + " account=98765" + (10000 + r.nextInt(89999)), """
                            \tat com.example.bank.deposit.ledger.LedgerClient.postCredit(LedgerClient.java:49)
                            \tat com.example.bank.deposit.DepositService.deposit(DepositService.java:36)
                            Caused by: org.springframework.web.client.HttpServerErrorException$ServiceUnavailable: 503 Service Unavailable
                            \tat com.example.bank.deposit.ledger.LedgerClient.call(LedgerClient.java:63)""",
                    14, 0.8, 0.9),
            new Failure("app-atm-balance-prod1", "com.example.bank.inquiry.CoreBankingClient", "ERROR",
                    "com.example.bank.inquiry.CoreBankingTimeoutException",
                    r -> "core banking balance call timed out after 2000ms pan=4111111111111111", """
                            \tat com.example.bank.inquiry.CoreBankingClient.balance(CoreBankingClient.java:52)
                            \tat com.example.bank.inquiry.BalanceService.balance(BalanceService.java:31)
                            Caused by: java.net.SocketTimeoutException: Read timed out
                            \tat com.example.bank.inquiry.CoreBankingClient.call(CoreBankingClient.java:70)""",
                    17, 0.30, 0.34),
            // Logged at INFO by a fallback: the word "Exception" is there, a stack trace is not
            new Failure("app-atm-balance-prod1", "com.example.bank.inquiry.component.InvokeBalanceWithCircuitBreaker",
                    "INFO", null, r -> "In downstreamBalanceFallback with Exception", null, 9, 0, 1),
            // A UI event: no trace id prefix, the ATM id and session are in the text
            new Failure("app-atm-ui-base-prod1", "com.example.bank.atm.api.controller.AtmUiController", "INFO", null,
                    r -> "\"CustomerCommSetup: Exception - undefined\"", null, 12, 0, 1),
            // Not a monitored component by default: shows that the search filters by component
            new Failure("app-atm-receipt-prod1", "com.example.bank.receipt.PrinterClient", "ERROR",
                    "com.example.bank.receipt.PrinterUnavailableException",
                    r -> "printer not ready state=PAPER_OUT retries=" + (1 + r.nextInt(3)), """
                            \tat com.example.bank.receipt.PrinterClient.print(PrinterClient.java:44)""",
                    118, 0, 1));

    private final Clock clock;
    private final JsonMapper json = new JsonMapper();

    public StubSplunkClient(Clock clock) {
        this.clock = clock;
    }

    @Override
    public List<Map<String, Object>> search(String name, Instant earliest, Instant latest, Map<String, Object> args) {
        if (!FAILED_TRANSACTIONS.equals(name)) {
            throw new IllegalArgumentException("Unknown search: " + name);
        }
        if (!NAMESPACE.equals(args.get("namespace"))) {
            return List.of();
        }
        List<Pattern> components = ((List<?>) args.get("components")).stream()
                .map(p -> Pattern.compile(Pattern.quote(String.valueOf(p)).replace("*", "\\E.*\\Q"))).toList();
        int limit = Integer.parseInt(String.valueOf(args.get("limit")));
        return generate().stream()
                .filter(e -> components.stream().anyMatch(p -> p.matcher(e.component).matches()))
                .filter(e -> !e.time.isBefore(earliest) && e.time.isBefore(latest))
                .limit(limit)
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("ts", e.time.toString());
                    row.put("_raw", e.raw);
                    return row;
                })
                .toList();
    }

    @Override
    public String description() {
        return "synthetic events (no Splunk)";
    }

    private record Event(Instant time, String component, String raw) {
    }

    /** The 24 hours ending at the current minute, oldest first. */
    private List<Event> generate() {
        Random rnd = new Random(SEED);
        Instant end = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        long dayMs = Duration.ofHours(24).toMillis();
        Instant start = end.minusMillis(dayMs);
        List<Event> events = new ArrayList<>();
        for (Failure f : FAILURES) {
            for (int i = 0; i < f.count(); i++) {
                double frac = f.fromFrac() + rnd.nextDouble() * (f.toFrac() - f.fromFrac());
                Instant time = start.plusMillis((long) (frac * (dayMs - 1000)));
                events.add(new Event(time, f.component(), raw(f, time, rnd)));
            }
        }
        events.sort(Comparator.comparing(Event::time));
        return events;
    }

    private String raw(Failure f, Instant time, Random rnd) {
        String atmId = ATM_IDS[rnd.nextInt(ATM_IDS.length)];
        String text = f.text().apply(rnd);
        String line;
        if (f.component().contains("-ui-")) {
            line = "UI MOD ATM ID:" + atmId + " Timestamp: " + time + " CustomerTrackingSessionId:"
                    + hex(rnd, 16).toUpperCase() + " " + text;
        } else {
            String trace = hex(rnd, 4) + "-" + hex(rnd, 2) + "-" + hex(rnd, 2) + "-" + hex(rnd, 2) + "-" + hex(rnd, 6);
            line = "--" + atmId + "-" + trace + "- " + (f.exception() == null ? text
                    : "attached exception: " + f.exception() + ": " + text + "\n" + f.stack());
        }
        String pod = f.component() + "-deploy-" + hex(new Random(f.component().hashCode()), 5) + "-"
                + (rnd.nextBoolean() ? "8j4q8" : "pzt8l");
        String message = LOCAL.format(time.atOffset(ZoneOffset.ofHours(-7))) + " -- LEVEL: " + f.level() + " "
                + f.logger() + " " + (100000000 + rnd.nextInt(899999999)) + " -[http-nio-8080-exec-"
                + (1 + rnd.nextInt(20)) + "] " + line;

        Map<String, Object> kubernetes = new LinkedHashMap<>();
        kubernetes.put("container_name", f.component());
        kubernetes.put("namespace_name", NAMESPACE);
        kubernetes.put("pod_name", pod);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("@timestamp", time.toString());
        event.put("hostname", "ocp-node-" + (10 + rnd.nextInt(8)) + ".example.net");
        event.put("kubernetes", kubernetes);
        event.put("level", f.level().toLowerCase());
        event.put("log_source", "container");
        event.put("log_type", "application");
        event.put("message", message);
        event.put("openshift", Map.of("labels", Map.of("clustername", "east1", "datacenter", "dc1")));
        return json.writeValueAsString(event);
    }

    private static String hex(Random rnd, int bytes) {
        byte[] b = new byte[bytes];
        rnd.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
