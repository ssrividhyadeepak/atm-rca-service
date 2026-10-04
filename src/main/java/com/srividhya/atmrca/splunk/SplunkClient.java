package com.srividhya.atmrca.splunk;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Runs one of the named searches configured for this service. Callers pick a name, a time
 * window and arguments; they cannot send search text of their own.
 */
public interface SplunkClient {

    /**
     * Failed transactions (events carrying an exception) for a list of transaction names.
     * Args: transactions (list of names), limit.
     * Row: ts, service, transaction, correlationId, terminal, exception, message, stackTrace.
     */
    String FAILED_TRANSACTIONS = "failed_transactions";

    /**
     * @param args values are a String or a List of Strings
     * @return one map per result row, oldest first
     */
    List<Map<String, Object>> search(String name, Instant earliest, Instant latest, Map<String, Object> args);

    /** For the startup summary and run records, e.g. "synthetic events" or the Splunk host and index. */
    String description();
}
