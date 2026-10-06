package com.srividhya.bankrca.rca;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.srividhya.bankrca.correlation.CorrelationResult;
import com.srividhya.bankrca.correlation.CorrelationResult.ComponentChain;
import com.srividhya.bankrca.correlation.CorrelationResult.FailureSignature;
import com.srividhya.bankrca.rca.RcaReport.Finding;
import com.srividhya.bankrca.source.SourceLocation;
import com.srividhya.bankrca.source.SourceLocation.CommitInfo;

/**
 * RCA without an LLM: a fixed set of rules over each signature. Every finding lists the rules
 * that fired and the evidence they used, so the reasoning can be checked and the same input
 * always gives the same report.
 *
 * Categories, tried in this order:
 * PROPAGATED (another component reporting a failure that started elsewhere),
 * CODE_DEFECT, DOWNSTREAM_TIMEOUT, DOWNSTREAM_UNAVAILABLE, EXPECTED_CONDITION,
 * HANDLED_FALLBACK, CLIENT_UI, and UNCLASSIFIED when nothing matches.
 */
@Component
public class RuleBasedRcaAnalyzer implements RcaAnalyzer {

    private static final List<String> DEFECT_EXCEPTIONS = List.of("NullPointerException", "IllegalStateException",
            "IllegalArgumentException", "ClassCastException", "IndexOutOfBoundsException",
            "ArrayIndexOutOfBoundsException", "NumberFormatException", "ArithmeticException",
            "ConcurrentModificationException", "UnsupportedOperationException");
    private static final Pattern TIMEOUT = Pattern.compile("(?i)timeout|timed out");
    private static final Pattern UNAVAILABLE = Pattern.compile(
            "(?i)\\b50[0-4]\\b|status=5\\d\\d|service ?unavailable|connection refused|connection reset|bad gateway");
    private static final Pattern EXPECTED = Pattern.compile(
            "(?i)insufficient|not ?found|rejected|declined|invalid|limit exceeded|expired");
    /** A change to the failing line this many days or fewer before the first failure is a suspect. */
    private static final int LINE_CHANGE_DAYS = 14;
    /** For a change elsewhere in the file the window is shorter: it is weaker evidence. */
    private static final int FILE_CHANGE_DAYS = 7;
    private static final List<String> SEVERITY_ORDER = List.of("HIGH", "MEDIUM", "LOW");

    private final Clock clock;

    public RuleBasedRcaAnalyzer(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return "rule-based/v1";
    }

    @Override
    public RcaReport analyze(RcaInput input) {
        CorrelationResult c = input.correlation();
        Map<String, FailureSignature> byId = new HashMap<>();
        c.signatures().forEach(s -> byId.put(s.id(), s));

        List<Finding> findings = new ArrayList<>();
        for (FailureSignature s : c.signatures()) {
            findings.add(finding(s, c, byId, input.knownSince().get(s.id()), input.source(s.id())));
        }
        findings.sort(Comparator.comparingInt((Finding f) -> SEVERITY_ORDER.indexOf(f.severity()))
                .thenComparing(f -> f.status().equals("NEW") ? 0 : 1)
                .thenComparing(Comparator.comparingInt(Finding::count).reversed()));
        List<Finding> ranked = new ArrayList<>();
        for (int i = 0; i < findings.size(); i++) {
            ranked.add(withRank(findings.get(i), i + 1));
        }

        RcaReport report = new RcaReport(c.to().substring(0, 10),
                clock.instant().truncatedTo(ChronoUnit.SECONDS).toString(), c.from(), c.to(), c.source(), name(),
                input.schemaVersion(), null, c.totalEvents(), c.truncated(),
                headline(ranked, c.totalEvents(), input.omittedSignatures()), ranked, null);
        return report.withMarkdown(RcaReportRenderer.render(report));
    }

    private Finding finding(FailureSignature s, CorrelationResult c, Map<String, FailureSignature> byId,
            String knownSince, SourceLocation source) {
        List<String> rules = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        List<String> related = new ArrayList<>();
        String simple = s.exception() == null ? null : s.exception().substring(s.exception().lastIndexOf('.') + 1);
        String text = (s.sampleMessage() == null ? "" : s.sampleMessage()) + "\n"
                + (s.sampleStackTrace() == null ? "" : s.sampleStackTrace());
        String rootCause = StackTraces.rootCause(s.sampleStackTrace());
        String location = StackTraces.location(s.sampleStackTrace());

        evidence.add(s.count() + " events, " + s.percentOfComponent() + "% of " + s.component() + "'s failures, "
                + "between " + s.firstSeen() + " and " + s.lastSeen());
        evidence.add("Time pattern " + s.timePattern() + (s.peakHour() == null ? ""
                : "; busiest hour " + s.peakHour() + " with " + s.peakHourCount() + " events"));
        evidence.add(s.pods() + " pod" + (s.pods() == 1 ? "" : "s") + " and " + s.bankIds() + " bank id"
                + (s.bankIds() == 1 ? "" : "s") + " affected");
        if (rootCause != null) {
            evidence.add("Stack trace root cause: " + rootCause);
        }
        if (location != null) {
            evidence.add("Thrown at " + location);
        }
        if (!s.sampleTraceIds().isEmpty()) {
            evidence.add("Sample trace ids: " + String.join(", ", s.sampleTraceIds()));
        }
        if (source != null && source.found()) {
            evidence.add("Source: " + source.repository() + " " + source.path() + ":" + source.line() + " at "
                    + source.ref() + ": `" + source.code() + "`");
            if (source.lineLastChanged() != null) {
                evidence.add("Line last changed: " + describe(source.lineLastChanged()));
            }
            if (source.fileLastChanged() != null && !source.fileLastChanged().equals(source.lineLastChanged())) {
                evidence.add("Latest commit to the file: " + describe(source.fileLastChanged()));
            }
        } else if (source != null) {
            evidence.add("Source not located: " + source.note());
        }

        // Where this signature sits in requests that failed in more than one component
        FailureSignature origin = null;
        for (ComponentChain chain : c.chains()) {
            if (!chain.signatureIds().contains(s.id())) {
                continue;
            }
            chain.signatureIds().stream().filter(id -> !id.equals(s.id())).forEach(related::add);
            int position = chain.components().indexOf(s.component());
            if (position > 0) {
                String first = chain.components().get(0);
                origin = chain.signatureIds().stream().map(byId::get)
                        .filter(o -> o != null && o.component().equals(first)).findFirst().orElse(origin);
                evidence.add(chain.traces() + " requests failed first in " + first + " and were then logged here "
                        + "(same trace id)");
            } else if (position == 0 && chain.components().size() > 1) {
                evidence.add(chain.traces() + " of these requests were also reported by "
                        + String.join(", ", chain.components().subList(1, chain.components().size()))
                        + " (same trace id)");
            }
        }

        String category;
        String title;
        String cause;
        String action;
        String confidence;
        if (origin != null) {
            rules.add("R1-propagated");
            category = "PROPAGATED";
            title = s.component() + " reports failures that started in " + origin.component();
            cause = "Not a separate problem: these lines are " + s.component() + " passing on the failure of "
                    + origin.component() + " (" + describe(origin) + ").";
            action = "Fix the origin; no action in " + s.component() + ".";
            confidence = "HIGH";
        } else if (simple != null && DEFECT_EXCEPTIONS.contains(simple)) {
            rules.add("R2-code-defect");
            category = "CODE_DEFECT";
            title = simple + " in " + s.component();
            cause = "A defect in the application code: " + simple + " is thrown by the code itself, not by a "
                    + "dependency." + (s.timePattern().equals("STEADY")
                            ? " It occurs across the whole window, so it is tied to a kind of request rather than to an outage."
                            : "");
            action = location == null ? "Find the failing line from a sample trace id and fix the missing check."
                    : "Fix the code at " + location + ".";
            confidence = location == null ? "MEDIUM" : "HIGH";
        } else if (TIMEOUT.matcher(text).find()) {
            rules.add("R3-timeout");
            category = "DOWNSTREAM_TIMEOUT";
            title = (simple == null ? "Timeouts" : simple) + " in " + s.component();
            cause = "A call to a dependency did not answer in time."
                    + (s.timePattern().equals("BURST") ? " The failures start and stop together, which points to a "
                            + "change or a slowdown in that period rather than a standing defect." : "");
            action = "Check what changed shortly before " + s.firstSeen() + " (deployments, timeout settings) and the "
                    + "dependency's response times in that period.";
            confidence = rootCause != null && rootCause.contains("Timeout") ? "HIGH" : "MEDIUM";
        } else if (UNAVAILABLE.matcher(text).find()) {
            rules.add("R4-unavailable");
            category = "DOWNSTREAM_UNAVAILABLE";
            title = (simple == null ? "Dependency errors" : simple) + " in " + s.component();
            cause = "A dependency answered with a server error or refused the connection.";
            action = "Check the dependency's health between " + s.firstSeen() + " and " + s.lastSeen()
                    + ", and whether that matches a maintenance window.";
            confidence = "MEDIUM";
        } else if (simple != null && EXPECTED.matcher(simple + " " + s.normalizedMessage()).find()) {
            rules.add("R5-expected-condition");
            category = "EXPECTED_CONDITION";
            title = simple + " in " + s.component();
            cause = "A business condition the application reports as an exception, not a malfunction.";
            action = "No fix needed unless the volume is unusual; consider logging it below ERROR.";
            confidence = "MEDIUM";
        } else if (simple == null && s.normalizedMessage().toLowerCase().contains("fallback")) {
            rules.add("R6-handled-fallback");
            category = "HANDLED_FALLBACK";
            title = "Fallback used in " + s.component();
            cause = "A fallback handled a failed downstream call. The request was served in a degraded way; the "
                    + "line does not say what failed.";
            action = "Find the failure behind the fallback from a sample trace id if the count grows.";
            confidence = "MEDIUM";
        } else if (simple == null && (s.normalizedMessage().startsWith("UI ") || s.sampleSessionIds().size() > 0)) {
            rules.add("R7-client-ui");
            category = "CLIENT_UI";
            title = "UI errors reported by " + s.component();
            cause = "An error raised in the user interface and logged by the server on its behalf.";
            action = "Look up a sample session to see which screen step fails.";
            confidence = "LOW";
        } else {
            rules.add("R0-unclassified");
            category = "UNCLASSIFIED";
            title = (simple == null ? "Failures" : simple) + " in " + s.component();
            cause = "No rule matches this failure.";
            action = "Needs a person to look at a sample trace id.";
            confidence = "LOW";
        }

        // A change to the failing line or its file shortly before the failures began is the first suspect.
        // The origin of a propagated failure is where to look, so that case is left alone.
        String suspectCommit = null;
        if (source != null && source.found() && origin == null) {
            Instant firstSeen = time(s.firstSeen());
            CommitInfo line = source.lineLastChanged();
            CommitInfo latest = source.fileLastChanged();
            if (changedShortlyBefore(line, firstSeen, LINE_CHANGE_DAYS)) {
                rules.add("R11-line-recently-changed");
                suspectCommit = line.hash();
                cause += " The failing line was last changed " + ago(line, firstSeen) + " before the first failure, by "
                        + describe(line) + ".";
                action = "Review commit " + line.hash() + " first. " + action;
            } else if (changedShortlyBefore(latest, firstSeen, FILE_CHANGE_DAYS)) {
                rules.add("R10-file-recently-changed");
                suspectCommit = latest.hash();
                cause += " The failing line itself is old, but its file was changed " + ago(latest, firstSeen)
                        + " before the first failure, by " + describe(latest) + ".";
                action = "Review commit " + latest.hash() + " first. " + action;
            }
        }

        if (s.pods() == 1 && s.count() >= 5) {
            rules.add("R8-single-pod");
            cause += " Only one pod is affected (" + s.podNames().get(0) + "), which points to that instance.";
        }
        if (s.bankIds() == 1 && s.count() >= 5) {
            rules.add("R9-single-bank-id");
            cause += " Only one bank id is affected (" + s.topBankIds().get(0).id() + ").";
        }

        return new Finding(0, s.id(), knownSince == null ? "NEW" : "RECURRING", knownSince,
                severity(s, category, rules), category, title, cause, confidence, evidence, action, location, rootCause,
                source, suspectCommit, related, rules, s.component(), s.transaction(), s.exception(), s.count(), s.percentOfComponent(),
                s.timePattern(), s.firstSeen(), s.lastSeen(), s.sampleTraceIds());
    }

    /**
     * By volume first; then a code defect of any size is raised (it will not go away on its
     * own), and findings that are someone else's failure or a handled condition are lowered.
     */
    private static String severity(FailureSignature s, String category, List<String> rules) {
        String severity = s.count() >= 50 || s.percentOfTotal() >= 25 ? "HIGH" : s.count() >= 10 ? "MEDIUM" : "LOW";
        if (category.equals("CODE_DEFECT") && s.count() >= 10) {
            rules.add("S1-defect-raised");
            return "HIGH";
        }
        if (List.of("PROPAGATED", "EXPECTED_CONDITION", "HANDLED_FALLBACK", "CLIENT_UI").contains(category)
                && !severity.equals("LOW")) {
            rules.add("S2-lowered");
            return "LOW";
        }
        return severity;
    }

    private static String describe(CommitInfo c) {
        return c.hash() + " " + c.time() + " " + c.author() + " \"" + c.message() + "\"";
    }

    /** True when the commit was made before the first failure and no more than {@code days} before it. */
    private static boolean changedShortlyBefore(CommitInfo commit, Instant firstSeen, int days) {
        Instant committed = commit == null ? null : time(commit.time());
        return committed != null && firstSeen != null && !committed.isAfter(firstSeen)
                && Duration.between(committed, firstSeen).toDays() < days;
    }

    private static String ago(CommitInfo commit, Instant firstSeen) {
        Duration d = Duration.between(time(commit.time()), firstSeen);
        return d.toHours() >= 48 ? d.toDays() + " days" : d.toMinutes() >= 120 ? d.toHours() + " hours"
                : d.toMinutes() + " minutes";
    }

    private static Instant time(String timestamp) {
        try {
            return timestamp == null ? null : Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String describe(FailureSignature s) {
        return s.exception() == null ? s.normalizedMessage()
                : s.exception().substring(s.exception().lastIndexOf('.') + 1);
    }

    private static String headline(List<Finding> findings, int totalEvents, int omitted) {
        if (findings.isEmpty()) {
            return "No failure events in this window.";
        }
        List<String> urgent = findings.stream().filter(f -> f.severity().equals("HIGH")).map(Finding::title).toList();
        long isNew = findings.stream().filter(f -> f.status().equals("NEW")).count();
        return findings.size() + " distinct problems in " + totalEvents + " failure events, " + isNew + " new. "
                + (urgent.isEmpty() ? "None is rated HIGH." : "Look first at: " + String.join("; ", urgent) + ".")
                + (omitted == 0 ? "" : " " + omitted + " less frequent problems were left out.");
    }

    private static Finding withRank(Finding f, int rank) {
        return new Finding(rank, f.signatureId(), f.status(), f.knownSince(), f.severity(), f.category(), f.title(),
                f.likelyCause(), f.confidence(), f.evidence(), f.suggestedAction(), f.location(),
                f.rootCauseException(), f.source(), f.suspectCommit(), f.relatedSignatureIds(), f.rules(), f.component(), f.transaction(),
                f.exception(), f.count(), f.percentOfComponent(), f.timePattern(), f.firstSeen(), f.lastSeen(),
                f.sampleTraceIds());
    }
}
