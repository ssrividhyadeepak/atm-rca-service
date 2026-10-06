package com.srividhya.bankrca.rca;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.srividhya.bankrca.correlation.CorrelationResult;
import com.srividhya.bankrca.correlation.CorrelationResult.ComponentChain;
import com.srividhya.bankrca.correlation.CorrelationResult.FailureSignature;
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

    private static final Logger log = LoggerFactory.getLogger(RcaInputBuilder.class);

    private final SourceRepository source;

    public RcaInputBuilder(SourceRepository source) {
        this.source = source;
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
        return new RcaInput(c.to().substring(0, 10), RcaInput.SCHEMA_VERSION, cut, known, sources,
                c.signatures().size() - kept.size());
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
