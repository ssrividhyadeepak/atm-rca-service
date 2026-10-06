package com.srividhya.bankrca.monitor;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;

import com.srividhya.bankrca.failure.FailureBatch.TransactionCount;

/**
 * One execution of the monitoring job: what window it looked at and how many failed
 * transactions it retrieved. The transactions themselves are not stored.
 *
 * @param trigger SCHEDULED, MANUAL or STARTUP
 * @param status COMPLETED or FAILED
 * @param signatures how many distinct problems the events grouped into; null on a failed run
 * @param topSignature the most frequent one, in a few words
 * @param note why a run failed, or that the result was cut off at the row cap
 */
public record MonitoringRun(
        @Id String id,
        Instant startedAt,
        Instant finishedAt,
        String trigger,
        String status,
        Instant windowFrom,
        Instant windowTo,
        String source,
        int failedTransactions,
        List<TransactionCount> byTransaction,
        Integer signatures,
        String topSignature,
        String note) {
}
