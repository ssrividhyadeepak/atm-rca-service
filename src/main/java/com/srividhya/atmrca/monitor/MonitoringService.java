package com.srividhya.atmrca.monitor;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.srividhya.atmrca.storage.RunStore;

/**
 * Runs one monitoring cycle and records it. Day 1: the cycle only records that it ran, which
 * proves the scheduler and storage work. Day 2 adds the Splunk query for failed transactions.
 */
@Service
public class MonitoringService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);

    private final RunStore store;
    private final Clock clock;

    public MonitoringService(RunStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** @param trigger SCHEDULED or MANUAL */
    public MonitoringRun run(String trigger) {
        Instant started = clock.instant();
        MonitoringRun run = store.save(new MonitoringRun(UUID.randomUUID().toString(), started, clock.instant(),
                trigger, "COMPLETED", "Heartbeat only: no monitoring source is connected yet"));
        log.info("Monitoring run {} ({}) recorded", run.id(), trigger);
        return run;
    }

    public List<MonitoringRun> latest(int limit) {
        return store.latest(limit);
    }
}
