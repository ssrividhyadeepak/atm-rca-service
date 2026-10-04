package com.srividhya.atmrca.monitor;

import java.time.Instant;

import org.springframework.data.annotation.Id;

/**
 * One execution of the monitoring job. From Day 2 it also records what was retrieved from Splunk.
 *
 * @param trigger SCHEDULED or MANUAL
 * @param status COMPLETED or FAILED
 */
public record MonitoringRun(
        @Id String id,
        Instant startedAt,
        Instant finishedAt,
        String trigger,
        String status,
        String note) {
}
