package com.srividhya.bankrca.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.srividhya.bankrca.config.RcaProperties;
import com.srividhya.bankrca.trace.TraceService;
import com.srividhya.bankrca.trace.TraceService.TraceNotFoundException;
import com.srividhya.bankrca.trace.TraceTimeline;

/** One request, followed across the components that logged it. */
@RestController
@RequestMapping("/api/traces")
public class TraceController {

    private final TraceService traces;
    private final Clock clock;
    private final Duration window;

    public TraceController(TraceService traces, Clock clock, RcaProperties props) {
        this.traces = traces;
        this.clock = clock;
        this.window = props.monitor().window();
    }

    @GetMapping("/{traceId}")
    public TraceTimeline trace(@PathVariable String traceId) {
        Instant to = clock.instant();
        return traces.trace(traceId, to.minus(window), to);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(TraceNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(TraceNotFoundException e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }
}
