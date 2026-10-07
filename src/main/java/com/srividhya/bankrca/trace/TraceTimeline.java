package com.srividhya.bankrca.trace;

import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * One request followed across every component that logged it, in time order, with the
 * bodies that were logged along the way (masked) and the point where it went wrong.
 *
 * @param outcome FAILED (an exception, or an error status sent back) or COMPLETED
 * @param components in the order they first logged
 * @param steps every log line of the request, oldest first
 * @param calls each call to a dependency, with its request and its answer paired up
 * @param divergence where the request left the normal path; null when nothing went wrong
 * @param truncated true when the request had more events than the cap
 */
public record TraceTimeline(
        String traceId,
        String bankId,
        String transaction,
        String outcome,
        String startedAt,
        String endedAt,
        long durationMs,
        List<String> components,
        List<Step> steps,
        List<Call> calls,
        Divergence divergence,
        boolean truncated,
        String source) {

    /**
     * @param kind IN (request received), OUT (call to a dependency), OUT_RESP (its answer),
     *        RESP (response sent), EXCEPTION, or LOG for any other line
     * @param offsetMs time since the first step
     * @param payload the body logged with the line, masked; null when there was none
     */
    public record Step(int index, String timestamp, long offsetMs, String component, String pod, String level,
            String kind, String target, String operation, Integer status, Long latencyMs, String error, String message,
            String exception, JsonNode payload) {
    }

    /**
     * @param outcome OK, FAILED (an error status or an error reported) or NO_RESPONSE (no answer was logged)
     * @param requestStep responseStep indexes into steps
     */
    public record Call(String from, String target, String operation, String outcome, Integer status, Long latencyMs,
            String error, Integer requestStep, Integer responseStep) {
    }

    /**
     * @param type TIMEOUT, DOWNSTREAM_ERROR, DECLINED, NULL_FIELD, MISSING_FIELD or EXCEPTION
     * @param where the component, and the dependency when the fault is in a call
     * @param detail what was observed, in a sentence
     * @param step the step that shows it
     */
    public record Divergence(String type, String where, String detail, int step) {
    }
}
