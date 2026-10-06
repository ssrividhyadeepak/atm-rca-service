package com.srividhya.bankrca.rca;

import static com.srividhya.bankrca.rca.RcaTestData.signature;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.srividhya.bankrca.correlation.CorrelationResult;
import com.srividhya.bankrca.correlation.CorrelationResult.ComponentChain;
import com.srividhya.bankrca.correlation.CorrelationResult.FailureSignature;
import com.srividhya.bankrca.rca.StackTraces.Frame;
import com.srividhya.bankrca.source.SourceLocation;
import com.srividhya.bankrca.source.SourceRepository;

class RcaInputBuilderTest {

    private final RcaInputBuilder builder = new RcaInputBuilder(RcaServiceTest.NO_SOURCE);

    @Test
    void cutsARealisticStackTraceDownToWhatMatters() {
        StringBuilder stack = new StringBuilder("com.acme.BadResponseException: Mandatory field missing\n");
        stack.append("\tat com.acme.clients.ResponseMapper.convert(ResponseMapper.java:340)\n");
        stack.append("\tat com.acme.profile.ProfileController.getSetup(ProfileController.java:211)\n");
        for (int i = 0; i < 120; i++) {
            stack.append("\tat org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:")
                    .append(100 + i).append(")\n");
        }
        stack.append("Caused by: java.net.SocketTimeoutException: Read timed out\n");
        stack.append("\tat java.base/sun.nio.ch.NioSocketImpl.timedRead(NioSocketImpl.java:278)\n");
        stack.append("\tat com.acme.clients.HttpCaller.call(HttpCaller.java:73)\n");
        stack.append("\t... 118 more");

        String trimmed = StackTraces.trim(stack.toString(), 8);

        assertThat(trimmed).isEqualTo("com.acme.BadResponseException: Mandatory field missing\n"
                + "\tat com.acme.clients.ResponseMapper.convert(ResponseMapper.java:340)\n"
                + "\tat com.acme.profile.ProfileController.getSetup(ProfileController.java:211)\n"
                + "\t... 120 other frames\n"
                + "Caused by: java.net.SocketTimeoutException: Read timed out\n"
                + "\t... 1 other frame\n"
                + "\tat com.acme.clients.HttpCaller.call(HttpCaller.java:73)\n"
                + "\t... 118 more");
        // What the analyzer reads from it is unchanged
        assertThat(StackTraces.rootCause(trimmed)).isEqualTo(StackTraces.rootCause(stack.toString()))
                .isEqualTo("java.net.SocketTimeoutException");
        assertThat(StackTraces.location(trimmed)).isEqualTo(StackTraces.location(stack.toString()))
                .isEqualTo("com.acme.clients.HttpCaller.call(HttpCaller.java:73)");
        assertThat(StackTraces.trim(null, 8)).isNull();
    }

    @Test
    void keepsTheMostFrequentSignaturesAndSaysHowManyWereLeftOut() {
        List<FailureSignature> signatures = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            signatures.add(signature("sig-" + i, "c-p1", "com.acme.E" + i, "m", null, 100 - i, "STEADY", 2, 2, 1));
        }
        CorrelationResult day = RcaTestData.day("2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z", signatures,
                List.of(new ComponentChain(List.of("a", "b"), 3, List.of("sig-0", "sig-55"), List.of("t")),
                        new ComponentChain(List.of("c", "d"), 2, List.of("sig-58", "sig-59"), List.of("t"))));

        RcaInput input = builder.build(day, Map.of("sig-1", "2026-09-01T00:00:00Z", "sig-57", "2026-09-01T00:00:00Z"));

        assertThat(input.id()).isEqualTo("2026-10-02");
        assertThat(input.schemaVersion()).isEqualTo("1.2");
        assertThat(input.correlation().signatures()).hasSize(50);
        assertThat(input.correlation().signatures().get(49).id()).isEqualTo("sig-49");
        assertThat(input.omittedSignatures()).isEqualTo(10);
        // Totals still describe the whole window
        assertThat(input.correlation().totalEvents()).isEqualTo(day.totalEvents());
        // Nothing points at a signature that is not in the input
        assertThat(input.correlation().chains()).hasSize(1);
        assertThat(input.correlation().chains().get(0).signatureIds()).containsExactly("sig-0");
        assertThat(input.knownSince()).containsOnlyKeys("sig-1");
        // These signatures have no stack trace, so there is nothing to look up
        assertThat(input.sources()).isEmpty();
    }

    @Test
    void looksUpTheRootCauseOfEachSignatureWithAStackTrace() {
        List<String> asked = new ArrayList<>();
        RcaInputBuilder withSource = new RcaInputBuilder(new SourceRepository() {
            @Override
            public String description() {
                return "test";
            }

            @Override
            public SourceLocation locate(String component, Frame frame) {
                asked.add(component + " " + frame.className() + "." + frame.method() + ":" + frame.line());
                if (frame.className().endsWith("Broken")) {
                    throw new IllegalStateException("git is down");
                }
                return new SourceLocation(true, null, "repo", "prod", frame.className(), frame.method(), "src/X.java",
                        frame.line(), "code();", "> 73 | code();", null, null, List.of());
            }
        });
        String stack = "com.acme.HostTimeoutException: timed out\n\tat com.acme.host.HostClient.authorize(HostClient.java:58)\n"
                + "Caused by: java.net.SocketTimeoutException: Read timed out\n\tat com.acme.host.HostClient$Caller.lambda$call$0(HostClient.java:73)";
        CorrelationResult day = RcaTestData.day(
                signature("timeout", "pay-p1", "com.acme.HostTimeoutException", "timed out", stack, 9, "BURST", 2, 2, 50),
                signature("broken", "pay-p1", "com.acme.X", "x", "com.acme.X: x\n\tat com.acme.Broken.run(Broken.java:5)", 5, "FEW", 2, 2, 30),
                signature("no-stack", "ui-p1", null, "UI failed", null, 4, "FEW", 2, 2, 20));

        RcaInput input = withSource.build(day, Map.of());

        // The root cause's application frame, with inner class and lambda names reduced to the outer class
        assertThat(asked).containsExactly("pay-p1 com.acme.host.HostClient.lambda$call$0:73", "pay-p1 com.acme.Broken.run:5");
        assertThat(input.sources()).containsOnlyKeys("timeout", "broken");
        assertThat(input.source("timeout").path()).isEqualTo("src/X.java");
        // A lookup that fails costs that signature its code context, not the whole input
        assertThat(input.source("broken").found()).isFalse();
        assertThat(input.source("broken").note()).isEqualTo("The source lookup failed: git is down");
        assertThat(input.source("no-stack")).isNull();
    }
}
