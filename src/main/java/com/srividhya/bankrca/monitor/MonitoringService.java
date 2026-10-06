package com.srividhya.bankrca.monitor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.srividhya.bankrca.config.RcaProperties;
import com.srividhya.bankrca.correlation.CorrelationResult;
import com.srividhya.bankrca.correlation.CorrelationResult.FailureSignature;
import com.srividhya.bankrca.correlation.CorrelationService;
import com.srividhya.bankrca.failure.FailureBatch;
import com.srividhya.bankrca.failure.FailureRetrievalService;
import com.srividhya.bankrca.rca.RcaReport;
import com.srividhya.bankrca.rca.RcaService;
import com.srividhya.bankrca.splunk.SplunkClient;
import com.srividhya.bankrca.storage.RunStore;

/**
 * Runs one monitoring cycle - retrieve the failure events of the look-back window, group them
 * into distinct problems and write the RCA report - and records the outcome. A cycle that cannot reach Splunk is recorded as FAILED, with the reason.
 */
@Service
public class MonitoringService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);

    private final FailureRetrievalService failures;
    private final CorrelationService correlation;
    private final RcaService rca;
    private final SplunkClient splunk;
    private final RunStore store;
    private final Clock clock;
    private final Duration window;

    public MonitoringService(FailureRetrievalService failures, CorrelationService correlation, RcaService rca,
            SplunkClient splunk, RunStore store, Clock clock, RcaProperties props) {
        this.failures = failures;
        this.correlation = correlation;
        this.rca = rca;
        this.splunk = splunk;
        this.store = store;
        this.clock = clock;
        this.window = props.monitor().window();
    }

    /** @param trigger SCHEDULED, MANUAL or STARTUP */
    public MonitoringRun run(String trigger) {
        Instant started = clock.instant();
        Instant from = started.minus(window);
        String id = UUID.randomUUID().toString();
        MonitoringRun run;
        try {
            FailureBatch batch = failures.retrieve(from, started);
            CorrelationResult correlated = correlation.correlate(batch);
            RcaReport report = rca.analyzeAndSave(correlated);
            String top = correlated.signatures().isEmpty() ? null : describe(correlated.signatures().get(0));
            run = new MonitoringRun(id, started, clock.instant(), trigger, "COMPLETED", from, started, batch.source(),
                    batch.total(), batch.byTransaction(), correlated.signatures().size(), top,
                    batch.truncated() ? "Result cut off at the row cap; there may be more failures" : null);
            log.info("Monitoring run {} ({}): {} failure events from {}", id, trigger, batch.total(), batch.source());
            log.info("RCA {}: {}", report.id(), report.headline());
        } catch (RuntimeException e) {
            run = new MonitoringRun(id, started, clock.instant(), trigger, "FAILED", from, started,
                    splunk.description(), 0, List.of(), null, null, e.getMessage());
            log.error("Monitoring run {} ({}) failed: {}", id, trigger, e.getMessage());
        }
        return store.save(run);
    }

    private static String describe(FailureSignature s) {
        String what = s.exception() == null ? s.normalizedMessage()
                : s.exception().substring(s.exception().lastIndexOf('.') + 1);
        return s.component() + " " + what + " x" + s.count() + " (" + s.timePattern() + ")";
    }

    public List<MonitoringRun> latest(int limit) {
        return store.latest(limit);
    }
}
