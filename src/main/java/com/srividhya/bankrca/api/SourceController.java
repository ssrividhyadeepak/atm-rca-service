package com.srividhya.bankrca.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.srividhya.bankrca.rca.StackTraces;
import com.srividhya.bankrca.rca.StackTraces.Frame;
import com.srividhya.bankrca.source.SourceRepository;
import com.srividhya.bankrca.source.SuspectService;

/**
 * Looks a stack trace up in the source code: send the trace as the request body and get back
 * the file, line, surrounding code and commits for the root cause's first application frame.
 * Also the quickest way to check that the git settings work.
 */
@RestController
@RequestMapping("/api/source")
public class SourceController {

    private static final int MAX_CHARS = 100_000;

    private final SourceRepository source;

    private final SuspectService suspects;
    private final Clock clock;

    public SourceController(SourceRepository source, SuspectService suspects, Clock clock) {
        this.suspects = suspects;
        this.clock = clock;
        this.source = source;
    }

    @PostMapping(value = "/locate", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<?> locate(@RequestBody String stackTrace,
            @RequestParam(required = false) String component) {
        if (stackTrace.length() > MAX_CHARS) {
            return ResponseEntity.badRequest().body(Map.of("error", "Stack trace too long; the most is " + MAX_CHARS + " characters"));
        }
        Frame frame = StackTraces.locationFrame(stackTrace);
        if (frame == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No application frame found. Expected lines like "
                    + "'at com.example.Foo.bar(Foo.java:42)'"));
        }
        return ResponseEntity.ok(source.locate(component, frame));
    }

    /** Commits before a failure, ranked: see SuspectService. */
    @GetMapping("/suspects")
    public ResponseEntity<?> suspects(@RequestParam String component, @RequestParam String failureTime,
            @RequestParam(defaultValue = "168") int hours, @RequestParam(required = false) String failingClass) {
        try {
            if (hours < 1 || hours > 720) {
                throw new IllegalArgumentException("'hours' must be between 1 and 720");
            }
            Instant failure = Instant.parse(failureTime.strip());
            if (failure.isAfter(clock.instant().plusSeconds(300))) {
                throw new IllegalArgumentException("'failureTime' is in the future");
            }
            return ResponseEntity.ok(suspects.find(component.strip(), failure, Duration.ofHours(hours),
                    failingClass == null || failingClass.isBlank() ? null : failingClass.strip()));
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "'failureTime' must be a time such as 2026-10-02T09:15:00Z"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
