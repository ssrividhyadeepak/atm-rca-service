package com.srividhya.bankrca.splunk;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.srividhya.bankrca.config.RcaProperties;
import com.srividhya.bankrca.splunk.StubData.FollowOn;
import com.srividhya.bankrca.splunk.StubData.StubFailure;
import com.srividhya.bankrca.splunk.StubData.TraceStep;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stand-in for Splunk on a local run: one synthetic day of failure events, described by a
 * JSON file you can edit (rca.stub.file, config/stub-failures.json by default). The file is
 * read again on every search, so an edit shows up in the next run without a restart. If the
 * file is not there, the copy built into the jar is used.
 *
 * The same file gives the same day every time (fixed seed), always ending "now". Each event is
 * raw JSON in the shape the container platform writes - @timestamp, hostname,
 * kubernetes{container_name, namespace_name, pod_name}, level, message, openshift.labels - with
 * the application log line, its tracing metadata and stack trace inside "message".
 */
@Component
@ConditionalOnProperty(name = "rca.splunk.mode", havingValue = "stub", matchIfMissing = true)
public class StubSplunkClient implements SplunkClient {

    static final String BUNDLED = "stub-failures.json";
    private static final long SEED = 42;
    private static final int MAX_EVENTS = 50_000;
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss,SSSxxx");
    private static final Pattern RANDOM_INT = Pattern.compile("\\{int:(\\d+)-(\\d+)\\}");

    private final Clock clock;
    private final Path file;
    private final JsonMapper json = JsonMapper.builder()
            // A misspelt field name is reported, not silently ignored
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public StubSplunkClient(Clock clock, RcaProperties props) {
        this.clock = clock;
        String configured = props.stub() == null ? null : props.stub().file();
        this.file = Path.of(configured == null || configured.isBlank() ? "config/stub-failures.json" : configured);
    }

    @Override
    public List<Map<String, Object>> search(String name, Instant earliest, Instant latest, Map<String, Object> args) {
        if (!FAILED_TRANSACTIONS.equals(name) && !TRACE_EVENTS.equals(name)) {
            throw new IllegalArgumentException("Unknown search: " + name);
        }
        StubData data = load();
        if (!data.namespace().equals(args.get("namespace"))) {
            return List.of();
        }
        int limit = Integer.parseInt(String.valueOf(args.get("limit")));
        Generated day = generate(data);
        List<Event> matched;
        if (TRACE_EVENTS.equals(name)) {
            // Everything logged for one request, by any component
            matched = day.traces().getOrDefault(String.valueOf(args.get("traceId")), List.of());
        } else {
            List<Pattern> components = ((List<?>) args.get("components")).stream()
                    .map(p -> Pattern.compile(Pattern.quote(String.valueOf(p)).replace("*", "\\E.*\\Q"))).toList();
            matched = day.failures().stream()
                    .filter(e -> components.stream().anyMatch(p -> p.matcher(e.component).matches())).toList();
        }
        return matched.stream()
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
        return "synthetic events from " + source() + " (no Splunk)";
    }

    private String source() {
        return Files.isRegularFile(file) ? file.toString() : "the built-in " + BUNDLED;
    }

    /** Reads and checks the file; every problem is reported with the place to fix it. */
    StubData load() {
        String where = source();
        StubData data;
        try {
            data = Files.isRegularFile(file) ? json.readValue(Files.readString(file), StubData.class) : bundled();
        } catch (JacksonException e) {
            throw new IllegalStateException(where + " is not valid: " + e.getOriginalMessage()
                    + (e.getLocation() == null ? "" : " (line " + e.getLocation().getLineNr() + ")"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        require(data.namespace() != null && !data.namespace().isBlank(), where, "'namespace' is required");
        require(data.bankIds() != null && !data.bankIds().isEmpty(), where, "'bankIds' needs at least one id");
        require(data.failures() != null && !data.failures().isEmpty(), where, "'failures' needs at least one entry");
        long total = 0;
        for (int i = 0; i < data.failures().size(); i++) {
            StubFailure f = data.failures().get(i);
            String entry = "failure #" + (i + 1) + ": ";
            require(hasText(f.component()), where, entry + "'component' is required");
            require(hasText(f.logger()), where, entry + "'logger' is required");
            require(hasText(f.message()), where, entry + "'message' is required");
            require(f.count() != null && f.count() >= 0, where, entry + "'count' is required and cannot be negative");
            double from = f.fromHoursAgo() == null ? 24 : f.fromHoursAgo();
            double to = f.toHoursAgo() == null ? 0 : f.toHoursAgo();
            require(from <= 24 && to >= 0 && from > to, where,
                    entry + "'fromHoursAgo' must be greater than 'toHoursAgo', both between 24 and 0");
            List<FollowOn> followOns = f.alsoLoggedBy() == null ? List.of() : f.alsoLoggedBy();
            for (FollowOn also : followOns) {
                require(hasText(also.component()) && hasText(also.logger()) && hasText(also.message()), where,
                        entry + "each 'alsoLoggedBy' entry needs 'component', 'logger' and 'message'");
                require(!Boolean.TRUE.equals(f.uiEvent()), where,
                        entry + "'alsoLoggedBy' cannot be used with 'uiEvent': UI events have no trace id to share");
            }
            for (TraceStep step : f.trace() == null ? List.<TraceStep>of() : f.trace()) {
                require(hasText(step.component()) && hasText(step.logger()) && hasText(step.text())
                        && List.of("IN", "OUT", "OUT_RESP", "RESP").contains(step.kind()), where,
                        entry + "each 'trace' step needs 'component', 'logger', 'text' and a 'kind' of IN, OUT, OUT_RESP or RESP");
            }
            total += (long) f.count() * (1 + followOns.size());
        }
        require(total <= MAX_EVENTS, where, "the counts add up to " + total + "; the most is " + MAX_EVENTS);
        return data;
    }

    private StubData bundled() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(BUNDLED)) {
            if (in == null) {
                throw new IllegalStateException("No stub data: " + file + " does not exist and the jar has no " + BUNDLED);
            }
            return json.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8), StubData.class);
        }
    }

    private static void require(boolean ok, String where, String problem) {
        if (!ok) {
            throw new IllegalStateException(where + " is not valid: " + problem);
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private record Event(Instant time, String component, String raw) {
    }

    /**
     * @param failures the events that mention an exception: what the failure search returns
     * @param traces trace id to every event of that request, in time order
     */
    private record Generated(List<Event> failures, Map<String, List<Event>> traces) {
    }

    /** The 24 hours ending at the current minute, oldest first. */
    private Generated generate(StubData data) {
        Random rnd = new Random(SEED);
        Instant end = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        List<Event> events = new ArrayList<>();
        Map<String, List<Event>> traces = new HashMap<>();
        for (StubFailure f : data.failures()) {
            long fromMs = hoursToMillis(f.fromHoursAgo() == null ? 24 : f.fromHoursAgo());
            long toMs = Math.max(1000, hoursToMillis(f.toHoursAgo() == null ? 0 : f.toHoursAgo()));
            for (int i = 0; i < f.count(); i++) {
                Instant time = end.minusMillis(fromMs - (long) (rnd.nextDouble() * (fromMs - toMs)));
                String bankId = data.bankIds().get(rnd.nextInt(data.bankIds().size()));
                String trace = hex(rnd, 4) + "-" + hex(rnd, 2) + "-" + hex(rnd, 2) + "-" + hex(rnd, 2) + "-" + hex(rnd, 6);
                String prefix = "--" + bankId + "-" + trace + "- ";
                String text = fill(f.message(), rnd);
                boolean ui = Boolean.TRUE.equals(f.uiEvent());
                String line = ui
                        ? "UI MOD BANK ID:" + bankId + " Timestamp: " + time + " CustomerTrackingSessionId:"
                                + hex(rnd, 16).toUpperCase() + " " + text
                        : prefix + (hasText(f.exception()) ? "attached exception: " + f.exception() + ": " + text : text)
                                + stack(f.stackTrace());
                List<Event> request = new ArrayList<>();
                Event failure = new Event(time, f.component(), raw(data, f.component(), f.logger(), f.level(), line, time, rnd));
                events.add(failure);
                request.add(failure);
                if (f.alsoLoggedBy() != null) {
                    // The same request, seen a moment later by the next component up the call path
                    Instant later = time;
                    for (FollowOn also : f.alsoLoggedBy()) {
                        later = later.plusMillis(5 + rnd.nextInt(40));
                        Event followOn = new Event(later, also.component(), raw(data, also.component(), also.logger(),
                                also.level(), prefix + fill(also.message(), rnd), later, rnd));
                        events.add(followOn);
                        request.add(followOn);
                    }
                }
                if (!ui) {
                    if (f.trace() != null) {
                        // Its own random source, so adding trace lines to the file does not change
                        // the failure events generated after it
                        Random own = new Random(trace.hashCode());
                        // One amount for the whole request: the same value must be seen at every hop
                        String amount = String.valueOf(20 + own.nextInt(49) * 20);
                        for (TraceStep step : f.trace()) {
                            Instant at = time.plusMillis(step.offsetMs() == null ? 0 : step.offsetMs());
                            String payload = step.payload() == null || step.payload().isNull() ? ""
                                    : " payload=" + fill(json.writeValueAsString(step.payload()).replace("{bankId}", bankId)
                                            .replace("{amount}", amount), own);
                            request.add(new Event(at, step.component(), raw(data, step.component(), step.logger(),
                                    hasText(step.level()) ? step.level() : "INFO",
                                    prefix + step.kind() + " " + fill(step.text(), own) + payload, at, own)));
                        }
                    }
                    request.sort(Comparator.comparing(Event::time));
                    traces.put(trace, request);
                }
            }
        }
        events.sort(Comparator.comparing(Event::time));
        return new Generated(events, traces);
    }

    private static long hoursToMillis(double hours) {
        return (long) (hours * Duration.ofHours(1).toMillis());
    }

    /** One event as the platform writes it, around the application's log line. */
    private String raw(StubData data, String component, String logger, String configuredLevel, String line,
            Instant time, Random rnd) {
        String level = hasText(configuredLevel) ? configuredLevel.toUpperCase() : "ERROR";
        String pod = component + "-deploy-" + hex(new Random(component.hashCode()), 5) + "-"
                + (rnd.nextBoolean() ? "8j4q8" : "pzt8l");
        String message = LOCAL.format(time.atOffset(ZoneOffset.ofHours(-7))) + " -- LEVEL: " + level + " "
                + logger + " " + (100000000 + rnd.nextInt(899999999)) + " -[http-nio-8080-exec-"
                + (1 + rnd.nextInt(20)) + "] " + line;

        Map<String, Object> kubernetes = new LinkedHashMap<>();
        kubernetes.put("container_name", component);
        kubernetes.put("namespace_name", data.namespace());
        kubernetes.put("pod_name", pod);
        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("clustername", data.cluster() == null ? "local" : data.cluster());
        labels.put("datacenter", data.datacenter() == null ? "local" : data.datacenter());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("@timestamp", time.toString());
        event.put("hostname", "ocp-node-" + (10 + rnd.nextInt(8)) + ".example.net");
        event.put("kubernetes", kubernetes);
        event.put("level", level.toLowerCase());
        event.put("log_source", "container");
        event.put("log_type", "application");
        event.put("message", message);
        event.put("openshift", Map.of("labels", labels));
        return json.writeValueAsString(event);
    }

    /** {int:1-4} becomes a random number from 1 to 4. */
    private static String fill(String template, Random rnd) {
        Matcher m = RANDOM_INT.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int low = Integer.parseInt(m.group(1));
            int high = Math.max(low, Integer.parseInt(m.group(2)));
            m.appendReplacement(out, String.valueOf(low + rnd.nextInt(high - low + 1)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Frames get the tab a real stack trace has; "Caused by" lines start at the margin. */
    private static String stack(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.strip();
            sb.append('\n').append(trimmed.startsWith("at ") || trimmed.startsWith("...") ? "\t" : "").append(trimmed);
        }
        return sb.toString();
    }

    private static String hex(Random rnd, int bytes) {
        byte[] b = new byte[bytes];
        rnd.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
