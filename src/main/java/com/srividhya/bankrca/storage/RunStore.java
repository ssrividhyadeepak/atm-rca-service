package com.srividhya.bankrca.storage;

import java.util.List;

import com.srividhya.bankrca.monitor.MonitoringRun;

/**
 * Where monitoring runs are kept. Two implementations, chosen by rca.storage: in memory for
 * local runs, MongoDB in the enterprise environment. Callers do not know which.
 */
public interface RunStore {

    MonitoringRun save(MonitoringRun run);

    /** Most recent first. */
    List<MonitoringRun> latest(int limit);

    long count();

    /** For the health endpoint and startup summary, e.g. "in-memory" or "MongoDB database bank_rca". */
    String description();

    /** @throws RuntimeException when the store cannot be reached */
    void ping();
}
