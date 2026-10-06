package com.srividhya.bankrca.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.srividhya.bankrca.correlation.CorrelationResult;
import com.srividhya.bankrca.correlation.CorrelationService;

/** The failure events of the last N hours, grouped into distinct problems. */
@RestController
@RequestMapping("/api/correlation")
public class CorrelationController {

    private static final int MAX_HOURS = 24;

    private final CorrelationService correlation;
    private final Clock clock;

    public CorrelationController(CorrelationService correlation, Clock clock) {
        this.correlation = correlation;
        this.clock = clock;
    }

    @GetMapping
    public CorrelationResult latest(@RequestParam(defaultValue = "24") int hours) {
        Instant to = clock.instant();
        return correlation.correlate(to.minus(Duration.ofHours(Math.max(1, Math.min(hours, MAX_HOURS)))), to);
    }
}
