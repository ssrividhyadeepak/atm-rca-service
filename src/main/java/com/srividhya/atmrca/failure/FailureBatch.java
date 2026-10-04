package com.srividhya.atmrca.failure;

import java.util.List;

/**
 * The failed transactions of one time window.
 *
 * @param source where they came from, e.g. the Splunk host and index
 * @param truncated true when the search hit its row cap, so there may be more
 * @param byTransaction count per transaction, largest first
 */
public record FailureBatch(
        String from,
        String to,
        String source,
        int total,
        boolean truncated,
        List<TransactionCount> byTransaction,
        List<FailedTransaction> items) {

    public record TransactionCount(String transaction, int count) {
    }
}
