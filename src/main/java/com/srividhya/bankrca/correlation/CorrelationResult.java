package com.srividhya.bankrca.correlation;

import java.util.List;

import com.srividhya.bankrca.failure.FailureBatch.ComponentCount;

/**
 * The failure events of one window, grouped into distinct problems.
 *
 * @param signatures one per distinct problem, most frequent first
 * @param chains components that logged failures for the same request (same trace id), most frequent first
 * @param truncated true when the search hit its row cap, so counts may be too low
 * @param unparsed events that were not in the expected format
 */
public record CorrelationResult(
        String from,
        String to,
        String source,
        int totalEvents,
        boolean truncated,
        int unparsed,
        List<ComponentCount> byComponent,
        List<FailureSignature> signatures,
        List<ComponentChain> chains) {

    /**
     * Events that are the same problem: same component, same exception and the same message
     * once the parts that change per request (numbers, ids) are replaced by placeholders.
     *
     * @param id stable id of the signature: the same problem has the same id on another day
     * @param exception fully qualified class, or null when the events name none
     * @param normalizedMessage the message with numbers and ids replaced, used for grouping
     * @param sampleMessage one real message, masked
     * @param percentOfComponent share of this component's failure events
     * @param timePattern BURST (nearly all within a quarter of the window), STEADY (across at
     *        least half of it), SCATTERED (in between) or FEW (under five events)
     * @param peakHour the clock hour (UTC) with the most events, and peakHourCount how many
     * @param pods how many different pods logged it; podNames lists up to five
     * @param bankIds how many different bank ids it affected; topBankIds the five most affected
     * @param sampleTraceIds up to three trace ids to follow up: the first, a middle and the last
     */
    public record FailureSignature(
            String id,
            String transaction,
            String component,
            String exception,
            String level,
            String logger,
            String normalizedMessage,
            String sampleMessage,
            int count,
            double percentOfComponent,
            double percentOfTotal,
            String firstSeen,
            String lastSeen,
            String timePattern,
            String peakHour,
            int peakHourCount,
            int pods,
            List<String> podNames,
            int bankIds,
            List<IdCount> topBankIds,
            List<String> sampleTraceIds,
            List<String> sampleSessionIds,
            String sampleStackTrace) {
    }

    public record IdCount(String id, int count) {
    }

    /**
     * @param components in the order they logged, e.g. [deposit-p1, gateway-p1]
     * @param traces how many requests followed this path
     * @param signatureIds the signatures involved
     */
    public record ComponentChain(List<String> components, int traces, List<String> signatureIds,
            List<String> sampleTraceIds) {
    }
}
