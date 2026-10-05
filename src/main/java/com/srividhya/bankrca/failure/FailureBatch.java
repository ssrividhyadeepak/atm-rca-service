package com.srividhya.bankrca.failure;

import java.util.List;

/**
 * The failure events of one time window.
 *
 * @param source where they came from, e.g. the Splunk host and index
 * @param truncated true when the search hit its row cap, so there may be more
 * @param unparsed events that were not in the expected format; they are still in items, with fewer fields
 * @param byTransaction count per transaction label, largest first
 * @param byComponent count per component, largest first
 */
public record FailureBatch(
        String from,
        String to,
        String source,
        int total,
        boolean truncated,
        int unparsed,
        List<TransactionCount> byTransaction,
        List<ComponentCount> byComponent,
        List<FailedTransaction> items) {

    public record TransactionCount(String transaction, int count) {
    }

    public record ComponentCount(String component, int count) {
    }
}
