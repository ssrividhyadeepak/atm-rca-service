package com.srividhya.bankrca.rca;

import static com.srividhya.bankrca.rca.RcaTestData.day;
import static com.srividhya.bankrca.rca.RcaTestData.signature;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.srividhya.bankrca.correlation.CorrelationResult;
import com.srividhya.bankrca.correlation.CorrelationResult.ComponentChain;
import com.srividhya.bankrca.rca.RcaReport.Finding;
import com.srividhya.bankrca.source.SourceLocation;
import com.srividhya.bankrca.source.SourceLocation.CommitInfo;

class RuleBasedRcaAnalyzerTest {

    private static final String TIMEOUT_STACK = "com.acme.HostTimeoutException: timed out\n"
            + "\tat com.acme.host.HostClient.authorize(HostClient.java:58)\n"
            + "\tat org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:885)\n"
            + "Caused by: java.net.SocketTimeoutException: Read timed out\n"
            + "\tat java.base/sun.nio.ch.NioSocketImpl.timedRead(NioSocketImpl.java:278)\n"
            + "\tat com.acme.host.HostClient.call(HostClient.java:73)";

    private final RuleBasedRcaAnalyzer analyzer = new RuleBasedRcaAnalyzer(
            Clock.fixed(Instant.parse("2026-10-02T00:05:00Z"), ZoneOffset.UTC));

    private Finding only(CorrelationResult day) {
        return analyzer.analyze(RcaInput.of(day, Map.of())).findings().get(0);
    }

    @Test
    void aBurstOfTimeoutsPointsAtAChangeOrSlowdown() {
        Finding f = only(day(signature("s1", "pay-p1", "com.acme.HostTimeoutException", "timed out after <n>ms",
                TIMEOUT_STACK, 64, "BURST", 2, 6, 40)));

        assertThat(f.category()).isEqualTo("DOWNSTREAM_TIMEOUT");
        assertThat(f.severity()).isEqualTo("HIGH");
        assertThat(f.confidence()).isEqualTo("HIGH");
        assertThat(f.title()).isEqualTo("HostTimeoutException in pay-p1");
        assertThat(f.likelyCause()).contains("did not answer in time").contains("start and stop together");
        assertThat(f.suggestedAction()).contains("what changed shortly before 2026-10-01T13:00:00Z");
        assertThat(f.rootCauseException()).isEqualTo("java.net.SocketTimeoutException");
        // The application frame of the root cause, not the JDK frame above it or the outer exception's frame
        assertThat(f.location()).isEqualTo("com.acme.host.HostClient.call(HostClient.java:73)");
        assertThat(f.rules()).containsExactly("R3-timeout");
        assertThat(f.evidence()).contains("64 events, 50.0% of pay-p1's failures, between 2026-10-01T13:00:00Z and "
                + "2026-10-01T15:00:00Z", "2 pods and 6 bank ids affected",
                "Stack trace root cause: java.net.SocketTimeoutException");
    }

    @Test
    void aCodeDefectIsRatedHighEvenAtModestVolume() {
        Finding f = only(day(signature("s1", "deposit-p1", "java.lang.NullPointerException",
                "Cannot invoke \"Envelope.getCurrency()\" because \"envelope\" is null",
                "java.lang.NullPointerException: x\n\tat com.acme.DepositValidator.validate(DepositValidator.java:35)",
                22, "STEADY", 2, 6, 13)));

        assertThat(f.category()).isEqualTo("CODE_DEFECT");
        assertThat(f.severity()).isEqualTo("HIGH");
        assertThat(f.rules()).containsExactly("R2-code-defect", "S1-defect-raised");
        assertThat(f.likelyCause()).contains("defect in the application code").contains("across the whole window");
        assertThat(f.suggestedAction())
                .isEqualTo("Fix the code at com.acme.DepositValidator.validate(DepositValidator.java:35).");
        assertThat(f.rootCauseException()).isNull();
    }

    @Test
    void classifiesTheOtherKindsOfFailure() {
        assertThat(only(day(signature("s", "deposit-p1", "com.acme.LedgerException",
                "ledger posting failed status=<n>", "com.acme.LedgerException: ledger posting failed status=503", 14,
                "BURST", 2, 6, 9))).category()).isEqualTo("DOWNSTREAM_UNAVAILABLE");

        Finding expected = only(day(signature("s", "pay-p1", "com.acme.InsufficientCassetteException",
                "cassette <n> cannot dispense", null, 30, "STEADY", 2, 6, 10)));
        assertThat(expected.category()).isEqualTo("EXPECTED_CONDITION");
        assertThat(expected.severity()).as("a business condition is not urgent, whatever its volume").isEqualTo("LOW");
        assertThat(expected.rules()).contains("S2-lowered");

        assertThat(only(day(signature("s", "balance-p1", null, "In downstreamBalanceFallback with Exception", null, 9,
                "STEADY", 2, 5, 5))).category()).isEqualTo("HANDLED_FALLBACK");

        assertThat(only(day(signature("s", "ui-base-p1", null, "UI MOD BANK ID:<bankId> \"Setup: Exception\"", null,
                12, "STEADY", 2, 6, 7))).category()).isEqualTo("CLIENT_UI");

        Finding unknown = only(day(signature("s", "batch-p1", "com.acme.WeirdException", "something odd", null, 3,
                "FEW", 2, 3, 2)));
        assertThat(unknown.category()).isEqualTo("UNCLASSIFIED");
        assertThat(unknown.confidence()).isEqualTo("LOW");
        assertThat(unknown.suggestedAction()).contains("Needs a person");
    }

    @Test
    void aFailureReportedByAnotherComponentIsNotASecondProblem() {
        CorrelationResult day = day("2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z", List.of(
                signature("ledger", "deposit-p1", "com.acme.LedgerException", "posting failed status=<n>",
                        "com.acme.LedgerException: posting failed status=503", 14, "BURST", 2, 6, 50),
                signature("gw", "gateway-p1", null, "upstream answered status=<n>, passing the Exception on", null, 14,
                        "BURST", 2, 6, 50)),
                List.of(new ComponentChain(List.of("deposit-p1", "gateway-p1"), 14, List.of("ledger", "gw"),
                        List.of("t1"))));

        List<Finding> findings = analyzer.analyze(RcaInput.of(day, Map.of())).findings();

        Finding origin = findings.get(0);
        Finding propagated = findings.get(1);
        assertThat(origin.signatureId()).isEqualTo("ledger");
        assertThat(origin.category()).isEqualTo("DOWNSTREAM_UNAVAILABLE");
        assertThat(origin.evidence()).contains("14 of these requests were also reported by gateway-p1 (same trace id)");
        assertThat(origin.relatedSignatureIds()).containsExactly("gw");

        assertThat(propagated.category()).isEqualTo("PROPAGATED");
        assertThat(propagated.severity()).isEqualTo("LOW");
        assertThat(propagated.title()).isEqualTo("gateway-p1 reports failures that started in deposit-p1");
        assertThat(propagated.likelyCause()).contains("Not a separate problem").contains("LedgerException");
        assertThat(propagated.suggestedAction()).isEqualTo("Fix the origin; no action in gateway-p1.");
    }

    private static SourceLocation source(String lineChanged, String fileChanged) {
        CommitInfo line = new CommitInfo("line000001", lineChanged, "priya", "initial import");
        CommitInfo file = fileChanged == null ? line : new CommitInfo("file000002", fileChanged, "alex", "lower host timeout");
        return new SourceLocation(true, null, "payments", "prod", "com.acme.host.HostClient", "call",
                "pay/src/main/java/com/acme/host/HostClient.java", 73, "return http.post(endpoint, request, TIMEOUT_MS);",
                "> 73 | ...", line, file, List.of(file, line));
    }

    private Finding withSource(String exception, SourceLocation source) {
        CorrelationResult day = day(signature("s1", "pay-p1", exception, "timed out", TIMEOUT_STACK, 64, "BURST", 2, 6, 40));
        return analyzer.analyze(new RcaInput("2026-10-02", RcaInput.SCHEMA_VERSION, day, Map.of(), Map.of("s1", source), 0))
                .findings().get(0);
    }

    @Test
    void aRecentChangeToTheFileIsTheFirstSuspect() {
        // First failure 2026-10-01T13:00; the file was changed 73 minutes earlier, the line itself in August
        Finding f = withSource("com.acme.HostTimeoutException", source("2026-08-10T09:00:00Z", "2026-10-01T11:47:00Z"));

        assertThat(f.rules()).containsExactly("R3-timeout", "R10-file-recently-changed");
        assertThat(f.suspectCommit()).isEqualTo("file000002");
        assertThat(f.likelyCause()).endsWith("The failing line itself is old, but its file was changed 73 minutes before "
                + "the first failure, by file000002 2026-10-01T11:47:00Z alex \"lower host timeout\".");
        assertThat(f.suggestedAction()).startsWith("Review commit file000002 first. Check what changed");
        assertThat(f.evidence()).contains(
                "Source: payments pay/src/main/java/com/acme/host/HostClient.java:73 at prod: "
                        + "`return http.post(endpoint, request, TIMEOUT_MS);`",
                "Line last changed: line000001 2026-08-10T09:00:00Z priya \"initial import\"",
                "Latest commit to the file: file000002 2026-10-01T11:47:00Z alex \"lower host timeout\"");
        assertThat(f.source().path()).isEqualTo("pay/src/main/java/com/acme/host/HostClient.java");
    }

    @Test
    void aRecentChangeToTheFailingLineItselfOutranksTheFile() {
        Finding f = withSource("java.lang.NullPointerException", source("2026-09-27T10:00:00Z", "2026-10-01T11:47:00Z"));

        assertThat(f.rules()).contains("R11-line-recently-changed").doesNotContain("R10-file-recently-changed");
        assertThat(f.suspectCommit()).isEqualTo("line000001");
        assertThat(f.likelyCause()).contains("The failing line was last changed 4 days before the first failure");
    }

    @Test
    void oldOrLaterCommitsAreNotSuspects() {
        // Both commits months old
        Finding old = withSource("com.acme.HostTimeoutException", source("2026-06-01T09:00:00Z", "2026-07-01T09:00:00Z"));
        assertThat(old.suspectCommit()).isNull();
        assertThat(old.rules()).containsExactly("R3-timeout");

        // A commit made after the failures began cannot have caused them
        Finding later = withSource("com.acme.HostTimeoutException", source("2026-06-01T09:00:00Z", "2026-10-01T14:00:00Z"));
        assertThat(later.suspectCommit()).isNull();
    }

    @Test
    void saysSoWhenTheSourceWasNotLocated() {
        Finding f = withSource("com.acme.HostTimeoutException", SourceLocation.notFound("com.acme.host.HostClient",
                "call", 73, "com.acme.host.HostClient was not found in payments at prod"));

        assertThat(f.evidence()).contains("Source not located: com.acme.host.HostClient was not found in payments at prod");
        assertThat(f.suspectCommit()).isNull();
        // The location read from the stack trace is still reported
        assertThat(f.location()).isEqualTo("com.acme.host.HostClient.call(HostClient.java:73)");
    }

    @Test
    void notesWhenOnlyOnePodOrOneBankIdIsAffected() {
        Finding f = only(day(signature("s", "pay-p1", "com.acme.HostTimeoutException", "timed out", TIMEOUT_STACK, 20,
                "BURST", 1, 1, 30)));

        assertThat(f.rules()).contains("R8-single-pod", "R9-single-bank-id");
        assertThat(f.likelyCause()).contains("Only one pod is affected (pod-a)").contains("Only one bank id is affected (Q1231)");
    }

    @Test
    void ordersBySeverityThenNewBeforeRecurringThenVolume() {
        CorrelationResult day = day(
                signature("low", "a-p1", "com.acme.WeirdException", "odd", null, 4, "FEW", 2, 2, 2),
                signature("high-old", "b-p1", "com.acme.HostTimeoutException", "timed out", TIMEOUT_STACK, 90, "BURST", 2, 6, 40),
                signature("high-new", "c-p1", "com.acme.HostTimeoutException", "timed out", TIMEOUT_STACK, 60, "BURST", 2, 6, 30),
                signature("medium", "d-p1", "com.acme.HostTimeoutException", "timed out", TIMEOUT_STACK, 15, "BURST", 2, 6, 8));

        RcaReport report = analyzer.analyze(RcaInput.of(day, Map.of("high-old", "2026-09-20T08:00:00Z")));

        assertThat(report.findings()).extracting(Finding::signatureId)
                .containsExactly("high-new", "high-old", "medium", "low");
        assertThat(report.findings()).extracting(Finding::rank).containsExactly(1, 2, 3, 4);
        assertThat(report.findings().get(1).status()).isEqualTo("RECURRING");
        assertThat(report.findings().get(1).knownSince()).isEqualTo("2026-09-20T08:00:00Z");
        assertThat(report.headline()).isEqualTo("4 distinct problems in 169 failure events, 3 new. Look first at: "
                + "HostTimeoutException in c-p1; HostTimeoutException in b-p1.");
    }

    @Test
    void rendersTheSameReportEveryTime() {
        CorrelationResult day = day(signature("s1", "pay-p1", "com.acme.HostTimeoutException", "timed out",
                TIMEOUT_STACK, 64, "BURST", 2, 6, 40));

        RcaReport first = analyzer.analyze(RcaInput.of(day, Map.of()));
        RcaReport second = analyzer.analyze(RcaInput.of(day, Map.of()));

        assertThat(first).isEqualTo(second);
        assertThat(first.id()).isEqualTo("2026-10-02");
        assertThat(first.analyzer()).isEqualTo("rule-based/v1");
        assertThat(first.markdown()).startsWith("# Failure RCA: 2026-10-01T00:00:00Z to 2026-10-02T00:00:00Z")
                .contains("without an LLM")
                .contains("| 1 | HIGH | NEW | DOWNSTREAM_TIMEOUT | pay-p1 | `HostTimeoutException` | 64 | BURST |")
                .contains("## 1. HostTimeoutException in pay-p1").contains("- Likely cause: ").contains("- Next step: ")
                .contains("  - Thrown at com.acme.host.HostClient.call(HostClient.java:73)")
                .contains("- Signature s1; rules R3-timeout");
    }

    @Test
    void anEmptyWindowGivesAnEmptyReport() {
        RcaReport report = analyzer.analyze(RcaInput.of(day(), Map.of()));

        assertThat(report.findings()).isEmpty();
        assertThat(report.headline()).isEqualTo("No failure events in this window.");
        assertThat(report.markdown()).contains("No failure events in this window.");
    }
}
