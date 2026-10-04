package com.srividhya.atmrca.splunk;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stand-in for Splunk on a local run: one synthetic day of failed ATM transactions, the same
 * day every time (fixed seed) but always ending "now", so "the last 24 hours" is never empty.
 * All data is fake; card numbers are the published test numbers.
 */
@Component
@ConditionalOnProperty(name = "rca.splunk.mode", havingValue = "stub", matchIfMissing = true)
public class StubSplunkClient implements SplunkClient {

    private static final long SEED = 42;
    private static final String[] TERMINALS = { "ATM-SF-0142", "ATM-SF-0188", "ATM-SF-0203", "ATM-SF-0217",
            "ATM-OAK-0031", "ATM-SJ-0077" };

    /** fromFrac and toFrac: the part of the day (0-1) in which the failure occurs. */
    private record Failure(String transaction, String service, String exception, Function<Random, String> message,
            String stack, int count, double fromFrac, double toFrac) {
    }

    private static final List<Failure> FAILURES = List.of(
            new Failure("cash-withdrawal", "withdrawal-service", "com.example.bank.withdrawal.host.HostAuthTimeoutException",
                    r -> "host authorization timed out after 500ms (attempt 2/2)", """
                            \tat com.example.bank.withdrawal.host.HostAuthClient.authorize(HostAuthClient.java:58)
                            \tat com.example.bank.withdrawal.WithdrawalService.withdraw(WithdrawalService.java:41)
                            Caused by: java.net.SocketTimeoutException: Read timed out
                            \tat com.example.bank.withdrawal.host.HostAuthClient.call(HostAuthClient.java:73)""",
                    64, 0.55, 0.65),
            new Failure("cash-withdrawal", "withdrawal-service", "com.example.bank.withdrawal.cash.InsufficientCassetteException",
                    r -> "cassette " + (1 + r.nextInt(4)) + " cannot dispense amount=" + (20 * (1 + r.nextInt(20))), """
                            \tat com.example.bank.withdrawal.cash.CassettePlanner.plan(CassettePlanner.java:66)
                            \tat com.example.bank.withdrawal.WithdrawalService.withdraw(WithdrawalService.java:47)""",
                    11, 0, 1),
            new Failure("cash-deposit", "deposit-service", "java.lang.NullPointerException",
                    r -> "Cannot invoke \"com.example.bank.deposit.model.Envelope.getCurrency()\" because \"envelope\" is null", """
                            \tat com.example.bank.deposit.validation.DepositValidator.validate(DepositValidator.java:35)
                            \tat com.example.bank.deposit.DepositService.deposit(DepositService.java:29)""",
                    22, 0, 1),
            new Failure("cash-deposit", "deposit-service", "com.example.bank.deposit.ledger.LedgerPostingException",
                    r -> "ledger posting failed status=503 attempt=" + (1 + r.nextInt(3)) + " account=98765" + (10000 + r.nextInt(89999)), """
                            \tat com.example.bank.deposit.ledger.LedgerClient.postCredit(LedgerClient.java:49)
                            \tat com.example.bank.deposit.DepositService.deposit(DepositService.java:36)
                            Caused by: org.springframework.web.client.HttpServerErrorException$ServiceUnavailable: 503 Service Unavailable
                            \tat com.example.bank.deposit.ledger.LedgerClient.call(LedgerClient.java:63)""",
                    14, 0.8, 0.9),
            new Failure("balance-inquiry", "account-inquiry-service", "com.example.bank.inquiry.CoreBankingTimeoutException",
                    r -> "core banking balance call timed out after 2000ms pan=4111111111111111", """
                            \tat com.example.bank.inquiry.CoreBankingClient.balance(CoreBankingClient.java:52)
                            \tat com.example.bank.inquiry.BalanceService.balance(BalanceService.java:31)
                            Caused by: java.net.SocketTimeoutException: Read timed out
                            \tat com.example.bank.inquiry.CoreBankingClient.call(CoreBankingClient.java:70)""",
                    17, 0.30, 0.34),
            new Failure("balance-inquiry", "account-inquiry-service", "com.example.bank.inquiry.CacheTimeoutException",
                    r -> "balance cache lookup timed out after " + (50 + r.nextInt(30)) + "ms", """
                            \tat com.example.bank.inquiry.BalanceCache.get(BalanceCache.java:33)
                            \tat com.example.bank.inquiry.BalanceService.balance(BalanceService.java:26)""",
                    9, 0, 1),
            // Not in the monitored list by default: shows that the search filters by transaction
            new Failure("receipt-print", "receipt-service", "com.example.bank.receipt.PrinterUnavailableException",
                    r -> "printer not ready state=PAPER_OUT retries=" + (1 + r.nextInt(3)), """
                            \tat com.example.bank.receipt.PrinterClient.print(PrinterClient.java:44)""",
                    118, 0, 1));

    private final Clock clock;

    public StubSplunkClient(Clock clock) {
        this.clock = clock;
    }

    @Override
    public List<Map<String, Object>> search(String name, Instant earliest, Instant latest, Map<String, Object> args) {
        if (!FAILED_TRANSACTIONS.equals(name)) {
            throw new IllegalArgumentException("Unknown search: " + name);
        }
        List<?> transactions = (List<?>) args.get("transactions");
        int limit = Integer.parseInt(String.valueOf(args.get("limit")));
        return generate().stream()
                .filter(e -> transactions.contains(e.get("transaction")))
                .filter(e -> {
                    Instant ts = Instant.parse((String) e.get("ts"));
                    return !ts.isBefore(earliest) && ts.isBefore(latest);
                })
                .limit(limit)
                .toList();
    }

    @Override
    public String description() {
        return "synthetic events (no Splunk)";
    }

    /** The 24 hours ending at the current minute, oldest first. */
    private List<Map<String, Object>> generate() {
        Random rnd = new Random(SEED);
        Instant end = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        long dayMs = Duration.ofHours(24).toMillis();
        Instant start = end.minusMillis(dayMs);
        List<Map<String, Object>> events = new ArrayList<>();
        for (Failure f : FAILURES) {
            String simpleName = f.exception().substring(f.exception().lastIndexOf('.') + 1);
            for (int i = 0; i < f.count(); i++) {
                double frac = f.fromFrac() + rnd.nextDouble() * (f.toFrac() - f.fromFrac());
                byte[] id = new byte[6];
                rnd.nextBytes(id);
                String message = simpleName + ": " + f.message().apply(rnd);
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("ts", start.plusMillis((long) (frac * (dayMs - 1000))).toString());
                e.put("service", f.service());
                e.put("transaction", f.transaction());
                e.put("correlationId", "MSG-" + HexFormat.of().formatHex(id));
                e.put("terminal", TERMINALS[rnd.nextInt(TERMINALS.length)]);
                e.put("exception", f.exception());
                e.put("message", message);
                e.put("stackTrace", f.exception() + ": " + message.substring(simpleName.length() + 2) + "\n" + f.stack());
                events.add(e);
            }
        }
        events.sort(Comparator.comparing(e -> (String) e.get("ts")));
        return events;
    }
}
