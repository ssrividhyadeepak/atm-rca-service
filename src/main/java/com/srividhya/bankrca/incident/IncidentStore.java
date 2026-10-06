package com.srividhya.bankrca.incident;

import java.util.List;
import java.util.Optional;

/** Where incident drafts are kept: in memory locally, MongoDB with rca.storage=mongo. */
public interface IncidentStore {

    IncidentDraft save(IncidentDraft draft);

    Optional<IncidentDraft> find(String id);

    /** Most recently created first. */
    List<IncidentDraft> latest(int limit);

    /** Drafts for a signature that are still open or were submitted, newest first. */
    List<IncidentDraft> activeForSignature(String signatureId);
}
