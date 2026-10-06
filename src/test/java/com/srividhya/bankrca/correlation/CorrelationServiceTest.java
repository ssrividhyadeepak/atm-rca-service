package com.srividhya.bankrca.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.srividhya.bankrca.correlation.CorrelationResult.FailureSignature;
import com.srividhya.bankrca.failure.FailedTransaction;
import com.srividhya.bankrca.failure.FailureBatch;

/** Grouping and pattern rules on hand-built events, so each rule is checked on its own. */
class CorrelationServiceTest {

    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-02T00:00:00Z");

    private final CorrelationService service = new CorrelationService(null);
    private final List<FailedTransaction> events = new ArrayList<>();

    private void event(double hour, String component, String pod, String bankId, String traceId, String exception,
            String message) {
        events.add(new FailedTransaction(FROM.plusMillis((long) (hour * 3_600_000)).toString(), "label-" + component,
                component, pod, "prod", "east1", "host", "ERROR", "com.acme.Logger", "exec-1", bankId, traceId, null,
                exception, message, exception == null ? null : exception + ": " + message + "\n\tat com.acme.A.b(A.java:1)"));
    }

    private CorrelationResult correlate() {
        return service.correlate(new FailureBatch(FROM.toString(), TO.toString(), "test", events.size(), false, 0,
                List.of(), List.of(), events));
    }

    @Test
    void groupsEventsThatDifferOnlyInNumbersAndIds() {
        event(1, "pay-p1", "pod-a", "Q1231", "11111111-aaaa-bbbb-cccc-000000000001", "com.acme.TimeoutException",
                "timed out after 500ms for request 9f8e7d6c5b4a3210 card ************1111");
        event(2, "pay-p1", "pod-b", "Q0457", "11111111-aaaa-bbbb-cccc-000000000002", "com.acme.TimeoutException",
                "timed out after 2000ms for request 0123456789abcdef card ************4242");
        // Same text, another component: a different problem
        event(3, "ledger-p1", "pod-c", "Q0457", "11111111-aaaa-bbbb-cccc-000000000003", "com.acme.TimeoutException",
                "timed out after 500ms for request 9f8e7d6c5b4a3210 card ************1111");
        // Same component, another exception
        event(4, "pay-p1", "pod-a", "Q1231", "11111111-aaaa-bbbb-cccc-000000000004", "com.acme.RejectedException",
                "timed out after 500ms for request 9f8e7d6c5b4a3210 card ************1111");

        CorrelationResult r = correlate();

        assertThat(r.signatures()).hasSize(3);
        FailureSignature top = r.signatures().get(0);
        assertThat(top.count()).isEqualTo(2);
        assertThat(top.normalizedMessage()).isEqualTo("timed out after <n>ms for request <id> card <masked>");
        assertThat(top.sampleMessage()).contains("500ms");
        assertThat(top.percentOfComponent()).isEqualTo(66.7);
        assertThat(top.percentOfTotal()).isEqualTo(50.0);
        assertThat(top.pods()).isEqualTo(2);
        assertThat(top.bankIds()).isEqualTo(2);
        assertThat(top.sampleStackTrace()).startsWith("com.acme.TimeoutException");
        // The same problem gets the same id every time; different problems get different ids
        assertThat(top.id()).matches("[0-9a-f]{10}").isEqualTo(correlate().signatures().get(0).id());
        assertThat(r.signatures()).extracting(FailureSignature::id).doesNotHaveDuplicates();
    }

    @Test
    void replacesTheEventsOwnIdsBeforeGrouping() {
        // UI lines carry the bank id and session in the text; they are still one problem
        events.add(new FailedTransaction(FROM.plusSeconds(60).toString(), "ui", "ui-p1", "pod", "prod", null, null,
                "INFO", "L", "t", "Q1231", null, "AAAA1111BBBB2222", null,
                "UI MOD BANK ID:Q1231 CustomerTrackingSessionId:AAAA1111BBBB2222 \"Setup: Exception - undefined\"", null));
        events.add(new FailedTransaction(FROM.plusSeconds(120).toString(), "ui", "ui-p1", "pod", "prod", null, null,
                "INFO", "L", "t", "T1187", null, "CCCC3333DDDD4444", null,
                "UI MOD BANK ID:T1187 CustomerTrackingSessionId:CCCC3333DDDD4444 \"Setup: Exception - undefined\"", null));

        CorrelationResult r = correlate();

        assertThat(r.signatures()).hasSize(1);
        assertThat(r.signatures().get(0).normalizedMessage())
                .isEqualTo("UI MOD BANK ID:<bankId> CustomerTrackingSessionId:<sessionId> \"Setup: Exception - undefined\"");
        assertThat(r.signatures().get(0).exception()).isNull();
        assertThat(r.signatures().get(0).sampleSessionIds()).containsExactly("AAAA1111BBBB2222", "CCCC3333DDDD4444");
        assertThat(r.signatures().get(0).sampleTraceIds()).isEmpty();
    }

    @Test
    void describesWhenEachProblemHappens() {
        // 20 events inside 2 hours, plus one stray 10 hours earlier: still a burst
        event(3, "burst-p1", "pod", "Q1", null, "com.acme.A", "boom");
        for (int i = 0; i < 20; i++) {
            event(13 + i * 0.1, "burst-p1", "pod", "Q1", null, "com.acme.A", "boom");
        }
        for (int i = 0; i < 12; i++) {
            event(1 + i * 1.9, "steady-p1", "pod", "Q1", null, "com.acme.B", "boom");
        }
        for (int i = 0; i < 8; i++) {
            event(6 + i, "scattered-p1", "pod", "Q1", null, "com.acme.C", "boom");
        }
        event(5, "few-p1", "pod", "Q1", null, "com.acme.D", "boom");
        event(6, "few-p1", "pod", "Q1", null, "com.acme.D", "boom");

        CorrelationResult r = correlate();

        assertThat(r.signatures()).extracting(s -> s.component() + " " + s.timePattern()).containsExactly(
                "burst-p1 BURST", "steady-p1 STEADY", "scattered-p1 SCATTERED", "few-p1 FEW");
        FailureSignature burst = r.signatures().get(0);
        assertThat(burst.firstSeen()).isEqualTo("2026-10-01T03:00:00Z");
        assertThat(burst.peakHour()).isEqualTo("2026-10-01T13:00:00Z");
        assertThat(burst.peakHourCount()).isEqualTo(10);
    }

    @Test
    void reportsHowWidelyAProblemIsSpread() {
        for (int i = 0; i < 9; i++) {
            event(i, "one-pod-p1", "pod-only", i < 7 ? "Q1231" : "Q0457", "00000000-0000-0000-0000-00000000000" + i,
                    "com.acme.A", "boom");
        }

        FailureSignature s = correlate().signatures().get(0);

        assertThat(s.pods()).isEqualTo(1);
        assertThat(s.podNames()).containsExactly("pod-only");
        assertThat(s.bankIds()).isEqualTo(2);
        assertThat(s.topBankIds().get(0).id()).isEqualTo("Q1231");
        assertThat(s.topBankIds().get(0).count()).isEqualTo(7);
        // First, middle and last of nine
        assertThat(s.sampleTraceIds()).containsExactly("00000000-0000-0000-0000-000000000000",
                "00000000-0000-0000-0000-000000000004", "00000000-0000-0000-0000-000000000008");
    }

    @Test
    void findsRequestsThatFailedInMoreThanOneComponent() {
        for (int i = 0; i < 3; i++) {
            String trace = "aaaaaaaa-0000-0000-0000-00000000000" + i;
            event(2 + i, "gateway-p1", "pod", "Q1", trace, null, "upstream failed with Exception");
            event(2 + i - 0.001, "ledger-p1", "pod", "Q1", trace, "com.acme.LedgerException", "posting failed");
        }
        // Seen by one component only: not a chain
        event(9, "ledger-p1", "pod", "Q1", "bbbbbbbb-0000-0000-0000-000000000000", "com.acme.LedgerException",
                "posting failed");
        // No trace id at all
        event(10, "ui-p1", "pod", "Q1", null, null, "screen failed");

        CorrelationResult r = correlate();

        assertThat(r.chains()).hasSize(1);
        // In the order they logged: the ledger failed first, the gateway reported it
        assertThat(r.chains().get(0).components()).containsExactly("ledger-p1", "gateway-p1");
        assertThat(r.chains().get(0).traces()).isEqualTo(3);
        assertThat(r.chains().get(0).signatureIds()).hasSize(2)
                .allMatch(id -> r.signatures().stream().anyMatch(s -> s.id().equals(id)));
        assertThat(r.chains().get(0).sampleTraceIds()).hasSize(3);
    }

    @Test
    void anEmptyWindowGivesNoSignatures() {
        CorrelationResult r = correlate();

        assertThat(r.totalEvents()).isZero();
        assertThat(r.signatures()).isEmpty();
        assertThat(r.chains()).isEmpty();
        assertThat(CorrelationService.timePattern(List.of(), Duration.ofHours(24))).isEqualTo("FEW");
    }
}
