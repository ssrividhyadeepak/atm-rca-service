package com.srividhya.bankrca.storage;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.srividhya.bankrca.rca.RcaInput;
import com.srividhya.bankrca.rca.RcaReport;

/** Local storage: reports and signature history are lost on restart, so after a restart every problem is NEW again. */
@Component
@ConditionalOnProperty(name = "rca.storage", havingValue = "memory", matchIfMissing = true)
public class InMemoryRcaStore implements RcaStore {

    private final Map<String, RcaReport> reports = new ConcurrentHashMap<>();
    private final Map<String, RcaInput> inputs = new ConcurrentHashMap<>();
    private final Map<String, Instant> firstSeen = new ConcurrentHashMap<>();

    @Override
    public void saveInput(RcaInput input) {
        inputs.put(input.id(), input);
    }

    @Override
    public Optional<RcaInput> latestInput() {
        return inputs.values().stream().max(Comparator.comparing(RcaInput::id));
    }

    @Override
    public void saveReport(RcaReport report) {
        reports.put(report.id(), report);
    }

    @Override
    public Optional<RcaReport> latestReport() {
        return reports(1).stream().findFirst();
    }

    @Override
    public List<RcaReport> reports(int limit) {
        return reports.values().stream().sorted(Comparator.comparing(RcaReport::id).reversed()).limit(limit).toList();
    }

    @Override
    public Map<String, Instant> firstSeen(Collection<String> signatureIds) {
        Map<String, Instant> known = new HashMap<>();
        signatureIds.forEach(id -> {
            Instant t = firstSeen.get(id);
            if (t != null) {
                known.put(id, t);
            }
        });
        return known;
    }

    @Override
    public void recordSeen(Map<String, Instant[]> seen) {
        seen.forEach((id, times) -> firstSeen.merge(id, times[0], (old, now) -> now.isBefore(old) ? now : old));
    }
}
