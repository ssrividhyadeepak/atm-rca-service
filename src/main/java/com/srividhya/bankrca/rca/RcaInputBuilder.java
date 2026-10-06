package com.srividhya.bankrca.rca;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.srividhya.bankrca.correlation.CorrelationResult;
import com.srividhya.bankrca.correlation.CorrelationResult.ComponentChain;
import com.srividhya.bankrca.correlation.CorrelationResult.FailureSignature;
import com.srividhya.bankrca.knowledge.KnowledgeHit;
import com.srividhya.bankrca.knowledge.KnowledgeRef;
import com.srividhya.bankrca.knowledge.KnowledgeService;
import com.srividhya.bankrca.rca.StackTraces.Frame;
import com.srividhya.bankrca.source.SourceLocation;
import com.srividhya.bankrca.source.SourceRepository;

/**
 * Builds the daily RCA input from a correlation result. Free text was masked when the events
 * were read; here the input is cut down to size - the most frequent signatures only, each
 * stack trace reduced to its exception lines and application frames - and each root cause
 * is looked up in the deployed source code.
 */
@Component
public class RcaInputBuilder {

    static final int MAX_SIGNATURES = 50;
    static final int MAX_APPLICATION_FRAMES = 8;
    static final int MAX_PAST_RCAS = 2;

    private static final Logger log = LoggerFactory.getLogger(RcaInputBuilder.class);

    private final SourceRepository source;
    private final KnowledgeService knowledge;

    @Autowired
    public RcaInputBuilder(SourceRepository source, KnowledgeService knowledge) {
        this.source = source;
        this.knowledge = knowledge;
    }

    /** Without a knowledge base: nothing is attached. */
    public RcaInputBuilder(SourceRepository source) {
        this(source, null);
    }

    public RcaInput build(CorrelationResult c, Map<String, String> knownSince) {
        // Signatures arrive most frequent first
        List<FailureSignature> kept = c.signatures().stream().limit(MAX_SIGNATURES).map(RcaInputBuilder::trimmed).toList();
        // Looked up from the full stack trace, before it is cut down
        Map<String, SourceLocation> sources = new LinkedHashMap<>();
        c.signatures().stream().limit(MAX_SIGNATURES).forEach(s -> {
            Frame frame = StackTraces.locationFrame(s.sampleStackTrace());
            if (frame != null) {
                sources.put(s.id(), locate(s.component(), frame));
            }
        });
        Set<String> ids = kept.stream().map(FailureSignature::id).collect(Collectors.toSet());
        List<ComponentChain> chains = c.chains().stream()
                .map(ch -> new ComponentChain(ch.components(), ch.traces(),
                        ch.signatureIds().stream().filter(ids::contains).toList(), ch.sampleTraceIds()))
                .filter(ch -> !ch.signatureIds().isEmpty())
                .toList();
        CorrelationResult cut = new CorrelationResult(c.from(), c.to(), c.source(), c.totalEvents(), c.truncated(),
                c.unparsed(), c.byComponent(), kept, chains);
        Map<String, String> known = knownSince.entrySet().stream().filter(e -> ids.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        String day = c.to().substring(0, 10);
        Map<String, List<KnowledgeRef>> refs = new LinkedHashMap<>();
        if (knowledge != null) {
            for (FailureSignature s : kept) {
                List<KnowledgeRef> found = references(s, day);
                if (!found.isEmpty()) {
                    refs.put(s.id(), found);
                }
            }
        }
        return new RcaInput(day, RcaInput.SCHEMA_VERSION, cut, known, sources, refs,
                c.signatures().size() - kept.size());
    }

    /**
     * The best runbook and up to two past RCAs, and only those that really match. Past RCAs
     * from this day on are left out, so a run is not matched against its own earlier result.
     */
    private List<KnowledgeRef> references(FailureSignature s, String day) {
        String query = s.component() + " " + (s.exception() == null ? "" : s.exception() + " ") + s.normalizedMessage();
        List<KnowledgeRef> refs = new ArrayList<>();
        try {
            knowledge.search(query, KnowledgeService.RUNBOOK, 1, null).stream().filter(KnowledgeHit::related)
                    .forEach(h -> refs.add(ref(h)));
            knowledge.search(query, KnowledgeService.PAST_RCA, MAX_PAST_RCAS, day).stream().filter(KnowledgeHit::related)
                    .forEach(h -> refs.add(ref(h)));
        } catch (RuntimeException e) {
            log.warn("Knowledge lookup for signature {} failed: {}", s.id(), e.getMessage());
        }
        return refs;
    }

    private static KnowledgeRef ref(KnowledgeHit h) {
        return new KnowledgeRef(h.type(), h.id(), h.title(), h.matchedBy(), h.score(), h.metadata().get("date"),
                h.summary());
    }

    /** A lookup that fails (git unreachable, a broken stub file) costs the finding its code context, not the run. */
    private SourceLocation locate(String component, Frame frame) {
        try {
            return source.locate(component, frame);
        } catch (RuntimeException e) {
            log.warn("Source lookup for {} failed: {}", frame.className(), e.getMessage());
            return SourceLocation.notFound(frame.className(), frame.method(), frame.line(),
                    "The source lookup failed: " + e.getMessage());
        }
    }

    private static FailureSignature trimmed(FailureSignature s) {
        return new FailureSignature(s.id(), s.transaction(), s.component(), s.exception(), s.level(), s.logger(),
                s.normalizedMessage(), s.sampleMessage(), s.count(), s.percentOfComponent(), s.percentOfTotal(),
                s.firstSeen(), s.lastSeen(), s.timePattern(), s.peakHour(), s.peakHourCount(), s.pods(), s.podNames(),
                s.bankIds(), s.topBankIds(), s.sampleTraceIds(), s.sampleSessionIds(),
                StackTraces.trim(s.sampleStackTrace(), MAX_APPLICATION_FRAMES));
    }
}
