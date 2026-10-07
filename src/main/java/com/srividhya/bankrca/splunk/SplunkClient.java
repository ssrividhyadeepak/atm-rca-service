package com.srividhya.bankrca.splunk;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Runs one of the named searches configured for this service. Callers pick a name, a time
 * window and arguments; they cannot send search text of their own.
 */
public interface SplunkClient {

    /**
     * Raw failure events (events containing "exception") of the monitored components.
     * Args: namespace, components (list of container-name patterns, '*' allowed), limit.
     * Row: ts (Splunk's event time) and _raw (the event as indexed).
     */
    String FAILED_TRANSACTIONS = "failed_transactions";

    /**
     * Every event of one request, whatever component logged it and whether it failed or not.
     * Args: namespace, traceId, limit. Row: ts and _raw, oldest first.
     */
    String TRACE_EVENTS = "trace_events";

    /**
     * @param args values are a String or a List of Strings
     * @return one map per result row, oldest first
     */
    List<Map<String, Object>> search(String name, Instant earliest, Instant latest, Map<String, Object> args);

    /** For the startup summary and run records, e.g. "synthetic events" or the Splunk host and index. */
    String description();
}
