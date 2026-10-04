package com.srividhya.atmrca.monitor;

import java.util.List;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Shows under "monitoring" in /actuator/health: the outcome of the most recent run. A failed
 * run (for example Splunk unreachable) turns health DOWN until a run succeeds again.
 */
@Component("monitoring")
public class MonitoringHealthIndicator implements HealthIndicator {

    private final MonitoringService monitoring;

    public MonitoringHealthIndicator(MonitoringService monitoring) {
        this.monitoring = monitoring;
    }

    @Override
    public Health health() {
        List<MonitoringRun> latest = monitoring.latest(1);
        if (latest.isEmpty()) {
            return Health.unknown().withDetail("lastRun", "none yet").build();
        }
        MonitoringRun run = latest.get(0);
        Health.Builder health = "COMPLETED".equals(run.status()) ? Health.up() : Health.down();
        health.withDetail("lastRun", run.status()).withDetail("at", run.startedAt().toString())
                .withDetail("source", run.source()).withDetail("failedTransactions", run.failedTransactions());
        if (run.note() != null) {
            health.withDetail("note", run.note());
        }
        return health.build();
    }
}
