package com.srividhya.bankrca.rca;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.srividhya.bankrca.correlation.CorrelationResult;
import com.srividhya.bankrca.correlation.CorrelationResult.FailureSignature;
import com.srividhya.bankrca.storage.RcaStore;

/**
 * Produces and keeps the RCA of a window: looks up what is already known about each signature,
 * builds and saves the daily RCA input, runs the analyzer on it, saves the report and updates
 * the signature history.
 */
@Service
public class RcaService {

    private final RcaAnalyzer analyzer;
    private final RcaInputBuilder inputs;
    private final RcaInputFiles files;
    private final RcaStore store;

    public RcaService(RcaAnalyzer analyzer, RcaInputBuilder inputs, RcaInputFiles files, RcaStore store) {
        this.analyzer = analyzer;
        this.inputs = inputs;
        this.files = files;
        this.store = store;
    }

    public RcaReport analyzeAndSave(CorrelationResult correlation) {
        Instant windowFrom = Instant.parse(correlation.from());
        List<String> ids = correlation.signatures().stream().map(FailureSignature::id).toList();

        // A signature is "known" only if it was seen before this window began: a second run over
        // the same day must not turn this morning's new problem into a recurring one
        Map<String, String> knownSince = new HashMap<>();
        store.firstSeen(ids).forEach((id, first) -> {
            if (first.isBefore(windowFrom)) {
                knownSince.put(id, first.toString());
            }
        });

        // The input is built, saved and hashed first; the analyzer sees exactly what was saved
        RcaInput input = inputs.build(correlation, knownSince);
        String inputJson = files.toJson(input);
        files.write(input, inputJson);
        store.saveInput(input);

        RcaReport report = analyze(input).withInputHash(RcaInputFiles.hash(inputJson));
        store.saveReport(report);

        Map<String, Instant[]> seen = new HashMap<>();
        for (FailureSignature s : correlation.signatures()) {
            Instant first = time(s.firstSeen());
            Instant last = time(s.lastSeen());
            if (first != null && last != null) {
                seen.put(s.id(), new Instant[] { first, last });
            }
        }
        store.recordSeen(seen);
        return report;
    }

    /** Runs the analyzer on an input without saving anything: for replaying a past day. */
    public RcaReport analyze(RcaInput input) {
        if (!RcaInput.READABLE_VERSIONS.contains(input.schemaVersion())) {
            throw new IllegalArgumentException("Unsupported schemaVersion '" + input.schemaVersion()
                    + "'; this service reads " + String.join(" and ", RcaInput.READABLE_VERSIONS));
        }
        if (input.correlation() == null || input.correlation().signatures() == null) {
            throw new IllegalArgumentException("The input has no 'correlation.signatures'");
        }
        return analyzer.analyze(input);
    }

    public Optional<RcaInput> latestInput() {
        return store.latestInput();
    }

    public String toJson(RcaInput input) {
        return files.toJson(input);
    }

    public Optional<RcaReport> latest() {
        return store.latestReport();
    }

    public List<RcaReport> reports(int limit) {
        return store.reports(limit);
    }

    private static Instant time(String timestamp) {
        try {
            return timestamp == null ? null : Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
