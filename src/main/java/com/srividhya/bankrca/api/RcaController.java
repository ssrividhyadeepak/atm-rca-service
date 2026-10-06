package com.srividhya.bankrca.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.srividhya.bankrca.config.RcaProperties;
import com.srividhya.bankrca.correlation.CorrelationService;
import com.srividhya.bankrca.rca.RcaInput;
import com.srividhya.bankrca.rca.RcaReport;
import com.srividhya.bankrca.rca.RcaService;

/** RCA reports: the latest one as JSON or markdown, the recent ones, or a new one right now. */
@RestController
@RequestMapping("/api/rca")
public class RcaController {

    private static final int MAX_LIMIT = 31;

    private final RcaService rca;
    private final CorrelationService correlation;
    private final Clock clock;
    private final Duration window;

    public RcaController(RcaService rca, CorrelationService correlation, Clock clock, RcaProperties props) {
        this.rca = rca;
        this.correlation = correlation;
        this.clock = clock;
        this.window = props.monitor().window();
    }

    @GetMapping("/latest")
    public ResponseEntity<RcaReport> latest() {
        return rca.latest().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/latest.md", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> latestMarkdown() {
        return rca.latest()
                .map(r -> ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                        .body(r.markdown()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<RcaReport> recent(@RequestParam(defaultValue = "7") int limit) {
        return rca.reports(Math.max(1, Math.min(limit, MAX_LIMIT)));
    }

    /** The daily RCA input the latest report was built from (daily-rca-input.json). */
    @GetMapping(value = "/input", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> latestInput() {
        return rca.latestInput().map(i -> ResponseEntity.ok(rca.toJson(i)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The JSON Schema of the daily RCA input. */
    @GetMapping(value = "/input/schema", produces = MediaType.APPLICATION_JSON_VALUE)
    public Resource inputSchema() {
        return new ClassPathResource("schemas/daily-rca-input.schema.json");
    }

    /**
     * Runs the analyzer on an input sent in the request - a saved day, or one edited by hand -
     * and returns the report without saving it. Nothing is read from Splunk.
     */
    @PostMapping(value = "/replay", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RcaReport replay(@RequestBody RcaInput input) {
        return rca.analyze(input);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    /** Retrieves, correlates and analyzes now, and saves the result as today's report. */
    @PostMapping
    public RcaReport analyzeNow() {
        Instant to = clock.instant();
        return rca.analyzeAndSave(correlation.correlate(to.minus(window), to));
    }
}
