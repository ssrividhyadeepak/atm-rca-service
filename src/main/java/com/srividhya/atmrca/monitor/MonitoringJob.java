package com.srividhya.atmrca.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the monitoring cycle on a schedule (rca.monitor.cron, UTC). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "rca.monitor.enabled", havingValue = "true")
public class MonitoringJob {

    private static final Logger log = LoggerFactory.getLogger(MonitoringJob.class);

    private final MonitoringService monitoring;

    public MonitoringJob(MonitoringService monitoring) {
        this.monitoring = monitoring;
    }

    @Scheduled(cron = "${rca.monitor.cron}", zone = "UTC")
    public void run() {
        try {
            monitoring.run("SCHEDULED");
        } catch (RuntimeException e) {
            // One failed cycle must not stop the schedule
            log.error("Scheduled monitoring run failed", e);
        }
    }
}
