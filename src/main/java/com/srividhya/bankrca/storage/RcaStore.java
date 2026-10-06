package com.srividhya.bankrca.storage;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.srividhya.bankrca.rca.RcaInput;
import com.srividhya.bankrca.rca.RcaReport;

/**
 * RCA inputs and reports (one of each per day; a later run on the same day replaces them) and, for each signature,
 * when it was first and last seen - which is how a report tells a new problem from a
 * recurring one. In memory locally, MongoDB with rca.storage=mongo.
 */
public interface RcaStore {

    /** One input per day, like the report built from it. */
    void saveInput(RcaInput input);

    Optional<RcaInput> latestInput();

    void saveReport(RcaReport report);

    Optional<RcaReport> latestReport();

    /** Most recent first, without the rendered markdown being needed by the caller. */
    List<RcaReport> reports(int limit);

    /** Signature id to the earliest time it was ever seen, for the ids that are known. */
    Map<String, Instant> firstSeen(Collection<String> signatureIds);

    /** Remembers each signature's first and last event time; an earlier first-seen is never overwritten. */
    void recordSeen(Map<String, Instant[]> firstAndLastSeenBySignature);
}
