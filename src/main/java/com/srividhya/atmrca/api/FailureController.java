package com.srividhya.atmrca.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.srividhya.atmrca.failure.FailureBatch;
import com.srividhya.atmrca.failure.FailureRetrievalService;

/** The failed transactions of the last N hours, retrieved now. */
@RestController
@RequestMapping("/api/failures")
public class FailureController {

    private static final int MAX_HOURS = 24;

    private final FailureRetrievalService failures;
    private final Clock clock;

    public FailureController(FailureRetrievalService failures, Clock clock) {
        this.failures = failures;
        this.clock = clock;
    }

    @GetMapping
    public FailureBatch latest(@RequestParam(defaultValue = "24") int hours) {
        Instant to = clock.instant();
        return failures.retrieve(to.minus(Duration.ofHours(Math.max(1, Math.min(hours, MAX_HOURS)))), to);
    }
}
