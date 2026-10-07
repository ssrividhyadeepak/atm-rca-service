package com.srividhya.bankrca.splunk;

import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * The contents of the stub's JSON file: what the synthetic day of failure events looks like.
 *
 * @param namespace kubernetes.namespace_name of every event
 * @param bankIds the bank ids events are spread over
 */
public record StubData(String namespace, String cluster, String datacenter, List<String> bankIds,
        List<StubFailure> failures) {

    /**
     * One kind of failure, generated {@code count} times.
     *
     * @param note free text for whoever edits the file; not used
     * @param component the container that logs it (kubernetes.container_name)
     * @param logger the class named in the log line
     * @param level the application level in the log line; ERROR when left out
     * @param exception fully qualified exception class; leave out for a line with no exception
     * @param message the log text; {@code {int:1-4}} becomes a random number in that range
     * @param stackTrace the lines under the exception, without leading whitespace
     * @param fromHoursAgo toHoursAgo when it happens, in hours before now; the whole day (24 to 0) when left out
     * @param uiEvent true for the UI format: no trace id, the bank id and tracking session in the text
     * @param alsoLoggedBy other components that log a line for the same request (same trace id)
     *        shortly after each of these failures
     * @param trace the other log lines of the request, for the trace search
     */
    public record StubFailure(String note, String component, String logger, String level, String exception,
            String message, List<String> stackTrace, Integer count, Double fromHoursAgo, Double toHoursAgo,
            Boolean uiEvent, List<FollowOn> alsoLoggedBy, List<TraceStep> trace) {
    }

    /**
     * One log line of the request around a failure: what the components logged on the way in
     * and out. These lines carry the same trace id as the failure; they are returned by the
     * trace search, not by the failure search (they do not mention an exception).
     *
     * @param kind IN (request received), OUT (call to a dependency), OUT_RESP (its answer) or RESP (response sent)
     * @param text what follows the kind: "POST /v1/withdrawals", "fraud-p1 score",
     *        "fraud-p1 score status=200 latencyMs=35", "status=504 latencyMs=1171"
     * @param payload the JSON body logged with it; {bankId} and {int:1-4} are filled in
     * @param offsetMs when it was logged, relative to the failure event (negative = before)
     */
    public record TraceStep(String component, String logger, String level, String kind, String text, JsonNode payload,
            Integer offsetMs) {
    }

    /** A line another component logs for the same request. level is ERROR when left out. */
    public record FollowOn(String component, String logger, String level, String message) {
    }
}
