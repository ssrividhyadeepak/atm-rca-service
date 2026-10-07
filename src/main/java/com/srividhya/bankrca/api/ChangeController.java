package com.srividhya.bankrca.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.srividhya.bankrca.change.ChangeService;
import com.srividhya.bankrca.change.ChangeService.ChangeSearch;

/** Change requests for one component: what changed, and how it sits against a failure time. */
@RestController
@RequestMapping("/api/changes")
public class ChangeController {

    private final ChangeService changes;
    private final Clock clock;

    public ChangeController(ChangeService changes, Clock clock) {
        this.changes = changes;
        this.clock = clock;
    }

    @GetMapping
    public ChangeSearch changes(@RequestParam String component, @RequestParam(defaultValue = "72") int hours,
            @RequestParam(required = false) String failureTime) {
        if (hours < 1 || hours > 336) {
            throw new IllegalArgumentException("'hours' must be between 1 and 336");
        }
        Instant to = clock.instant();
        Instant failure;
        try {
            failure = failureTime == null || failureTime.isBlank() ? null : Instant.parse(failureTime.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'failureTime' must be a time such as 2026-10-02T09:15:00Z");
        }
        return changes.search(List.of(component.strip()), to.minus(Duration.ofHours(hours)), to, failure);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> unavailable(IllegalStateException e) {
        return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
    }
}
