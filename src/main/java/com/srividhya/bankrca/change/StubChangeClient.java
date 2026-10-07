package com.srividhya.bankrca.change;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sample change requests from config/stub-changes.json, so the change lookup runs with no
 * ServiceNow. The file is read on every call: edit it and ask again. Times are given as
 * hours before now, so the sample changes always sit next to the sample failures.
 */
@Component
@ConditionalOnProperty(name = "rca.change.mode", havingValue = "stub", matchIfMissing = true)
public class StubChangeClient implements ChangeClient {

    /** @param startedHoursAgo endedHoursAgo when the work was done, in hours before now */
    public record StubChange(String number, String component, String service, String type, String state, String risk,
            String shortDescription, String description, String assignmentGroup, Double startedHoursAgo,
            Double endedHoursAgo, String closeCode) {
    }

    public record StubChanges(String note, List<StubChange> changes) {
    }

    private static final String BUNDLED = "stub-changes.json";

    private final Clock clock;
    private final Path file;
    private final JsonMapper json = JsonMapper.builder()
            // A misspelt field name is reported, not silently ignored
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public StubChangeClient(Clock clock, ChangeProps props) {
        this.clock = clock;
        this.file = Path.of(props.stubFile() == null || props.stubFile().isBlank() ? "config/stub-changes.json"
                : props.stubFile());
    }

    @Override
    public String description() {
        return "sample changes from " + source() + " (no ServiceNow)";
    }

    @Override
    public List<ChangeRequest> changes(List<String> components, Instant from, Instant to, int limit) {
        Instant now = clock.instant();
        return load().stream()
                .filter(c -> components.contains(c.component()))
                .map(c -> new ChangeRequest(c.number(), c.component(), c.service() == null ? c.component() : c.service(),
                        c.type(), c.state(), c.risk(), c.shortDescription(), c.description(), c.assignmentGroup(),
                        ago(now, c.startedHoursAgo()).toString(), ago(now, c.endedHoursAgo()).toString(), c.closeCode()))
                // The work overlaps the window
                .filter(c -> !Instant.parse(c.startedAt()).isAfter(to) && !Instant.parse(c.endedAt()).isBefore(from))
                .sorted(Comparator.comparing(ChangeRequest::startedAt).reversed())
                .limit(limit)
                .toList();
    }

    private static Instant ago(Instant now, double hours) {
        return now.minus(Duration.ofSeconds(Math.round(hours * 3600))).truncatedTo(ChronoUnit.SECONDS);
    }

    private String source() {
        return Files.isRegularFile(file) ? file.toString() : "the built-in " + BUNDLED;
    }

    /** Reads and checks the file; every problem is reported with the place to fix it. */
    List<StubChange> load() {
        String where = source();
        StubChanges data;
        try {
            if (Files.isRegularFile(file)) {
                data = json.readValue(Files.readString(file), StubChanges.class);
            } else {
                try (InputStream in = getClass().getClassLoader().getResourceAsStream(BUNDLED)) {
                    if (in == null) {
                        throw new IllegalStateException("No sample changes: " + file + " does not exist");
                    }
                    data = json.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8), StubChanges.class);
                }
            }
        } catch (JacksonException e) {
            throw new IllegalStateException(where + " is not valid: " + e.getOriginalMessage());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (data.changes() == null) {
            throw new IllegalStateException(where + " needs a 'changes' list");
        }
        for (int i = 0; i < data.changes().size(); i++) {
            StubChange c = data.changes().get(i);
            String entry = where + ", changes[" + i + "]: ";
            if (blank(c.number()) || blank(c.component()) || blank(c.shortDescription())) {
                throw new IllegalStateException(entry + "'number', 'component' and 'shortDescription' are required");
            }
            if (c.startedHoursAgo() == null || c.endedHoursAgo() == null || c.endedHoursAgo() > c.startedHoursAgo()) {
                throw new IllegalStateException(entry + "'startedHoursAgo' and 'endedHoursAgo' are required, and the "
                        + "change must end after it starts (endedHoursAgo is the smaller number)");
            }
        }
        return data.changes();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
