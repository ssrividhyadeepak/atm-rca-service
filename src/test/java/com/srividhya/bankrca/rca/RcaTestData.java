package com.srividhya.bankrca.rca;

import java.util.List;

import com.srividhya.bankrca.correlation.CorrelationResult;
import com.srividhya.bankrca.correlation.CorrelationResult.ComponentChain;
import com.srividhya.bankrca.correlation.CorrelationResult.FailureSignature;
import com.srividhya.bankrca.correlation.CorrelationResult.IdCount;

/** Hand-built signatures, so each rule can be tested on exactly the input it is about. */
public final class RcaTestData {

    private RcaTestData() {
    }

    public static FailureSignature signature(String id, String component, String exception, String message,
            String stack, int count, String timePattern, int pods, int bankIds, double percentOfTotal) {
        return new FailureSignature(id, "label", component, exception, "ERROR", "com.acme.Logger", message, message,
                count, 50.0, percentOfTotal, "2026-10-01T13:00:00Z", "2026-10-01T15:00:00Z", timePattern,
                "2026-10-01T14:00:00Z", count / 2, pods, List.of("pod-a", "pod-b").subList(0, Math.min(pods, 2)),
                bankIds, List.of(new IdCount("Q1231", count)),
                List.of("11111111-2222-3333-4444-555555555555"), message.startsWith("UI ") ? List.of("SESSION1") : List.of(),
                stack);
    }

    public static CorrelationResult day(String from, String to, List<FailureSignature> signatures,
            List<ComponentChain> chains) {
        int total = signatures.stream().mapToInt(FailureSignature::count).sum();
        return new CorrelationResult(from, to, "test", total, false, 0, List.of(), signatures, chains);
    }

    public static CorrelationResult day(FailureSignature... signatures) {
        return day("2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z", List.of(signatures), List.of());
    }
}
