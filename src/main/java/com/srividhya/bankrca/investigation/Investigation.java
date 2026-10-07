package com.srividhya.bankrca.investigation;

import java.util.ArrayList;
import java.util.List;

/**
 * One finding under investigation: the evidence the service gathered for it, what a model
 * has concluded from it, what a developer has said about it, and what was approved. The
 * evidence is collected and numbered by code; everything written by a model refers to it by id.
 *
 * @param failureTime when the failures began: every timing in the evidence is measured against it
 * @param knownComponents the components this failure is known to involve: the one that failed and those in its trace
 * @param observedImpact what the logs show of the impact, in a sentence; computed, not written by a model
 * @param revision how many times hypotheses have been recorded
 * @param hypotheses the latest set, highest score first; empty until recorded
 * @param fixPlan the plan for the chosen hypothesis; null until recorded
 * @param pullRequest the draft pull request; null until an approved plan has been turned into one
 * @param history who did what, oldest first
 */
public record Investigation(String id, String reportId, int findingRank, String signatureId, String component,
        String transaction, String exception, String failureTime, List<String> knownComponents, String observedImpact,
        String createdAt, String createdBy, List<Evidence> evidence, int revision, List<Hypothesis> hypotheses,
        FixPlan fixPlan, PullRequest pullRequest, List<Event> history) {

    /**
     * One fact, in a sentence.
     *
     * @param id E1, E2 ...: what a hypothesis cites
     * @param type FINDING, TRACE, DIVERGENCE, SOURCE, CHANGE, COMMIT, RUNBOOK, PAST_RCA, or NOTE for what a developer added
     * @param ref what it is in its own system: a trace id, change number, commit hash, document id
     * @param points how much it adds to the score of a hypothesis that cites it; why says how that was decided
     * @param excluded set aside by a developer: still listed, worth nothing
     * @param addedBy who added a NOTE; null for evidence the service collected
     */
    public record Evidence(String id, String type, String ref, String time, String summary, int points, String why,
            boolean excluded, String excludedReason, String addedBy) {
    }

    /**
     * @param statement the model's words
     * @param modelConfidence what the model said about its own confidence; recorded, not used
     * @param score 0-95, from the evidence cited: how well supported the hypothesis is, not the odds it is right
     * @param level HIGH (70+), MEDIUM (40+) or LOW
     * @param scoreBreakdown each part of the score and the evidence it came from
     */
    public record Hypothesis(int rank, String statement, List<String> evidenceIds, String modelConfidence, int score,
            String level, List<String> scoreBreakdown, String recordedBy, String recordedAt) {
    }

    /**
     * @param hypothesis the statement the plan is for, copied when the plan was recorded
     * @param edits single-line changes to the deployed code, each checked against it
     * @param runbookMitigation what the matching runbook says to do, when there is one; from the runbook, not the model
     * @param warnings things a reviewer should look at, found by the service
     * @param status PROPOSED, APPROVED or REJECTED; only a person changes it
     */
    public record FixPlan(int hypothesisRank, String hypothesis, String summary, List<String> steps, String rollback,
            List<String> testPlan, BlastRadius blastRadius, List<Edit> edits, String runbookMitigation,
            List<String> warnings, String status, String proposedBy, String proposedAt, String decidedBy,
            String decidedAt, String decisionComment) {

        public FixPlan decided(String status, String by, String at, String comment) {
            return new FixPlan(hypothesisRank, hypothesis, summary, steps, rollback, testPlan, blastRadius, edits,
                    runbookMitigation, warnings, status, proposedBy, proposedAt, by, at, comment);
        }
    }

    /** @param components which components the fix touches or could affect */
    public record BlastRadius(List<String> components, String description) {
    }

    /** @param oldCode what the line is now, as verified against the deployed code */
    public record Edit(String path, int line, String oldCode, String newCode) {
    }

    public record PullRequest(String number, String url, String branch, String title, String system, String createdAt,
            String requestedBy) {
    }

    public record Event(String time, String who, String action, String detail) {
    }

    public Investigation with(List<Evidence> newEvidence, int newRevision, List<Hypothesis> newHypotheses,
            FixPlan newFixPlan, PullRequest newPullRequest, Event event) {
        List<Event> events = new ArrayList<>(history);
        events.add(event);
        return new Investigation(id, reportId, findingRank, signatureId, component, transaction, exception, failureTime,
                knownComponents, observedImpact, createdAt, createdBy, List.copyOf(newEvidence), newRevision,
                List.copyOf(newHypotheses), newFixPlan, newPullRequest, List.copyOf(events));
    }
}
