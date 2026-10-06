package com.srividhya.bankrca.rca;

import java.util.List;

import org.springframework.data.annotation.Id;

import com.srividhya.bankrca.knowledge.KnowledgeRef;
import com.srividhya.bankrca.source.SourceLocation;

/**
 * The root cause analysis of one window.
 *
 * @param id the day the window ends on (UTC), e.g. 2026-10-04; a later run on the same day replaces the report
 * @param analyzer which analyzer produced it
 * @param inputSchemaVersion version of the daily RCA input it was built from
 * @param inputHash SHA-256 of that input as saved, so the report can be matched to its exact input
 * @param headline one sentence: how many problems, and which need attention first
 * @param findings most urgent first
 * @param markdown the report rendered for people
 */
public record RcaReport(
        @Id String id,
        String generatedAt,
        String from,
        String to,
        String source,
        String analyzer,
        String inputSchemaVersion,
        String inputHash,
        int totalEvents,
        boolean truncated,
        String headline,
        List<Finding> findings,
        String markdown) {

    public RcaReport withMarkdown(String rendered) {
        return new RcaReport(id, generatedAt, from, to, source, analyzer, inputSchemaVersion, inputHash, totalEvents,
                truncated, headline, findings, rendered);
    }

    /** The markdown is rendered again, because it names the input. */
    public RcaReport withInputHash(String hash) {
        RcaReport withHash = new RcaReport(id, generatedAt, from, to, source, analyzer, inputSchemaVersion, hash,
                totalEvents, truncated, headline, findings, null);
        return withHash.withMarkdown(RcaReportRenderer.render(withHash));
    }

    /**
     * @param status NEW (first seen inside this window) or RECURRING (seen before; knownSince says when)
     * @param severity HIGH, MEDIUM or LOW
     * @param category what kind of problem the rules took it for
     * @param likelyCause the explanation the evidence supports best
     * @param confidence HIGH, MEDIUM or LOW: how much the evidence backs the category and cause
     * @param evidence the facts behind it, each one checkable against the events
     * @param location where in the application code the root cause was thrown, when a stack trace shows it
     * @param source the same place looked up in the deployed code: file, line, code and commits; null when there is no stack trace
     * @param suspectCommit a recent commit to that line or file that the rules point at; null when there is none
     * @param knowledge the runbook and past RCAs that match this problem
     * @param relatedSignatureIds other findings about the same requests
     * @param rules the rules that fired, for anyone asking why the report says this
     */
    public record Finding(
            int rank,
            String signatureId,
            String status,
            String knownSince,
            String severity,
            String category,
            String title,
            String likelyCause,
            String confidence,
            List<String> evidence,
            String suggestedAction,
            String location,
            String rootCauseException,
            SourceLocation source,
            String suspectCommit,
            List<KnowledgeRef> knowledge,
            List<String> relatedSignatureIds,
            List<String> rules,
            String component,
            String transaction,
            String exception,
            int count,
            double percentOfComponent,
            String timePattern,
            String firstSeen,
            String lastSeen,
            List<String> sampleTraceIds) {
    }
}
