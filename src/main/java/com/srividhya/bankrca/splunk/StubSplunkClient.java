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
import com.srividhya.bankrca.splunk.StubData.StubFailure;

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
        if (!FAILED_TRANSACTIONS.equals(name)) {
            throw new IllegalArgumentException("Unknown search: " + name);
        }
        StubData data = load();
        if (!data.namespace().equals(args.get("namespace"))) {
            return List.of();
        }
        List<Pattern> components = ((List<?>) args.get("components")).stream()
                .map(p -> Pattern.compile(Pattern.quote(String.valueOf(p)).replace("*", "\\E.*\\Q"))).toList();
        int limit = Integer.parseInt(String.valueOf(args.get("limit")));
        return generate(data).stream()
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
            total += f.count();
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

    /** The 24 hours ending at the current minute, oldest first. */
    private List<Event> generate(StubData data) {
        Random rnd = new Random(SEED);
        Instant end = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        List<Event> events = new ArrayList<>();
        for (StubFailure f : data.failures()) {
            long fromMs = hoursToMillis(f.fromHoursAgo() == null ? 24 : f.fromHoursAgo());
            long toMs = Math.max(1000, hoursToMillis(f.toHoursAgo() == null ? 0 : f.toHoursAgo()));
            for (int i = 0; i < f.count(); i++) {
                Instant time = end.minusMillis(fromMs - (long) (rnd.nextDouble() * (fromMs - toMs)));
                events.add(new Event(time, f.component(), raw(data, f, time, rnd)));
            }
        }
        events.sort(Comparator.comparing(Event::time));
        return events;
    }

    private static long hoursToMillis(double hours) {
        return (long) (hours * Duration.ofHours(1).toMillis());
    }

    private String raw(StubData data, StubFailure f, Instant time, Random rnd) {
        String bankId = data.bankIds().get(rnd.nextInt(data.bankIds().size()));
        String text = fill(f.message(), rnd);
        String line;
        if (Boolean.TRUE.equals(f.uiEvent())) {
            line = "UI MOD BANK ID:" + bankId + " Timestamp: " + time + " CustomerTrackingSessionId:"
                    + hex(rnd, 16).toUpperCase() + " " + text;
        } else {
            String trace = hex(rnd, 4) + "-" + hex(rnd, 2) + "-" + hex(rnd, 2) + "-" + hex(rnd, 2) + "-" + hex(rnd, 6);
            line = "--" + bankId + "-" + trace + "- "
                    + (hasText(f.exception()) ? "attached exception: " + f.exception() + ": " + text : text)
                    + stack(f.stackTrace());
        }
        String level = hasText(f.level()) ? f.level().toUpperCase() : "ERROR";
        String pod = f.component() + "-deploy-" + hex(new Random(f.component().hashCode()), 5) + "-"
                + (rnd.nextBoolean() ? "8j4q8" : "pzt8l");
        String message = LOCAL.format(time.atOffset(ZoneOffset.ofHours(-7))) + " -- LEVEL: " + level + " "
                + f.logger() + " " + (100000000 + rnd.nextInt(899999999)) + " -[http-nio-8080-exec-"
                + (1 + rnd.nextInt(20)) + "] " + line;

        Map<String, Object> kubernetes = new LinkedHashMap<>();
        kubernetes.put("container_name", f.component());
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
