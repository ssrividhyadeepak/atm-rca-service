package com.srividhya.atmrca.monitor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.srividhya.atmrca.config.RcaProperties;
import com.srividhya.atmrca.failure.FailureBatch;
import com.srividhya.atmrca.failure.FailureRetrievalService;
import com.srividhya.atmrca.splunk.SplunkClient;
import com.srividhya.atmrca.storage.RunStore;

/**
 * Runs one monitoring cycle - retrieve the failed transactions of the look-back window - and
 * records the outcome. A cycle that cannot reach Splunk is recorded as FAILED, with the reason.
 */
@Service
public class MonitoringService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);

    private final FailureRetrievalService failures;
    private final SplunkClient splunk;
    private final RunStore store;
    private final Clock clock;
    private final Duration window;

    public MonitoringService(FailureRetrievalService failures, SplunkClient splunk, RunStore store, Clock clock,
            RcaProperties props) {
        this.failures = failures;
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
            run = new MonitoringRun(id, started, clock.instant(), trigger, "COMPLETED", from, started, batch.source(),
                    batch.total(), batch.byTransaction(),
                    batch.truncated() ? "Result cut off at the row cap; there may be more failures" : null);
            log.info("Monitoring run {} ({}): {} failure events from {}: {}", id, trigger, batch.total(),
                    batch.source(), batch.byTransaction().stream().map(t -> t.transaction() + "=" + t.count()).toList());
        } catch (RuntimeException e) {
            run = new MonitoringRun(id, started, clock.instant(), trigger, "FAILED", from, started,
                    splunk.description(), 0, List.of(), e.getMessage());
            log.error("Monitoring run {} ({}) failed: {}", id, trigger, e.getMessage());
        }
        return store.save(run);
    }

    public List<MonitoringRun> latest(int limit) {
        return store.latest(limit);
    }
}
