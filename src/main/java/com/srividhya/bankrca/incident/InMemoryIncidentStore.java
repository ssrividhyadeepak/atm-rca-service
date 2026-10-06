package com.srividhya.bankrca.incident;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "rca.storage", havingValue = "memory", matchIfMissing = true)
public class InMemoryIncidentStore implements IncidentStore {

    private final Map<String, IncidentDraft> drafts = new ConcurrentHashMap<>();

    @Override
    public IncidentDraft save(IncidentDraft draft) {
        drafts.put(draft.id(), draft);
        return draft;
    }

    @Override
    public Optional<IncidentDraft> find(String id) {
        return Optional.ofNullable(drafts.get(id));
    }

    @Override
    public List<IncidentDraft> latest(int limit) {
        return drafts.values().stream().sorted(Comparator.comparing(IncidentDraft::createdAt).reversed()
                .thenComparing(IncidentDraft::id)).limit(limit).toList();
    }

    @Override
    public List<IncidentDraft> activeForSignature(String signatureId) {
        return drafts.values().stream()
                .filter(d -> d.signatureId().equals(signatureId) && !d.status().equals("REJECTED"))
                .sorted(Comparator.comparing(IncidentDraft::createdAt).reversed()).toList();
    }
}
