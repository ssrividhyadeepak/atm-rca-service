package com.srividhya.bankrca.storage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.srividhya.bankrca.monitor.MonitoringRun;

/** Local storage: nothing to install, nothing survives a restart. */
@Component
@ConditionalOnProperty(name = "rca.storage", havingValue = "memory", matchIfMissing = true)
public class InMemoryRunStore implements RunStore {

    private final Map<String, MonitoringRun> runs = new ConcurrentHashMap<>();

    @Override
    public MonitoringRun save(MonitoringRun run) {
        runs.put(run.id(), run);
        return run;
    }

    @Override
    public List<MonitoringRun> latest(int limit) {
        List<MonitoringRun> sorted = new ArrayList<>(runs.values());
        sorted.sort(Comparator.comparing(MonitoringRun::startedAt).reversed());
        return sorted.subList(0, Math.min(limit, sorted.size()));
    }

    @Override
    public long count() {
        return runs.size();
    }

    @Override
    public String description() {
        return "in-memory (lost on restart)";
    }

    @Override
    public void ping() {
    }
}
