package com.srividhya.bankrca.rca;

import java.util.List;
import java.util.Map;

import org.springframework.data.annotation.Id;

import com.srividhya.bankrca.correlation.CorrelationResult;
import com.srividhya.bankrca.knowledge.KnowledgeRef;
import com.srividhya.bankrca.source.SourceLocation;

/**
 * The daily RCA input (daily-rca-input.json): everything an analyzer is given for one window,
 * and nothing else. It is the boundary of what may leave the service - grouped, masked and
 * cut down to size - and it is saved, so any report can be traced back to its exact input and
 * an analyzer can be run again on a past day.
 *
 * The shape is fixed by schemas/daily-rca-input.schema.json; a test fails if the two drift apart.
 *
 * @param id the day the window ends on (UTC)
 * @param schemaVersion version of this contract; raised when a field changes meaning or is removed
 * @param correlation the grouped failures: signatures, component chains and totals
 * @param knownSince signature id to the time it was first ever seen, for signatures seen before this window
 * @param sources signature id to where its root cause is in the deployed code, for signatures with a stack trace
 * @param knowledge signature id to the runbook and past RCAs that match it; only signatures with a match are listed
 * @param omittedSignatures signatures left out because the window had more than the cap; the most frequent are kept
 */
public record RcaInput(
        @Id String id,
        String schemaVersion,
        CorrelationResult correlation,
        Map<String, String> knownSince,
        Map<String, SourceLocation> sources,
        Map<String, List<KnowledgeRef>> knowledge,
        int omittedSignatures) {

    /** 1.1 added 'sources', 1.2 added 'knowledge'. */
    public static final String SCHEMA_VERSION = "1.2";
    /** Versions this service can still read: the earlier ones only lack the fields added since. */
    public static final List<String> READABLE_VERSIONS = List.of("1.0", "1.1", "1.2");

    public static RcaInput of(CorrelationResult correlation, Map<String, String> knownSince) {
        return new RcaInput(correlation.to().substring(0, 10), SCHEMA_VERSION, correlation, knownSince, Map.of(), Map.of(), 0);
    }

    /** The source location of a signature, or null when there is none. */
    public SourceLocation source(String signatureId) {
        return sources == null ? null : sources.get(signatureId);
    }

    /** The runbook and past RCAs of a signature; empty when there are none. */
    public List<KnowledgeRef> knowledge(String signatureId) {
        List<KnowledgeRef> refs = knowledge == null ? null : knowledge.get(signatureId);
        return refs == null ? List.of() : refs;
    }
}
