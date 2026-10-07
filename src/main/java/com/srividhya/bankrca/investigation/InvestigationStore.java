package com.srividhya.bankrca.investigation;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Investigations are kept in memory, the newest 200: they are working state for a
 * conversation, and what is worth keeping ends up in an incident or a past RCA.
 */
@Component
public class InvestigationStore {

    private static final int MAX = 200;

    private final Map<String, Investigation> byId = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Investigation> eldest) {
            return size() > MAX;
        }
    };

    public synchronized Investigation save(Investigation investigation) {
        byId.put(investigation.id(), investigation);
        return investigation;
    }

    public synchronized Optional<Investigation> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public synchronized List<Investigation> latest(int limit) {
        return byId.values().stream().sorted(Comparator.comparing(Investigation::createdAt).reversed()
                .thenComparing(Investigation::id)).limit(limit).toList();
    }
}
