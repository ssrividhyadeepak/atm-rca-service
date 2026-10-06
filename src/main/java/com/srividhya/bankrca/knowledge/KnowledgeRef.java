package com.srividhya.bankrca.knowledge;

/**
 * A runbook or past RCA attached to a signature in the daily RCA input. Only matches strong
 * enough to act on are attached.
 *
 * @param type runbook or past-rca
 * @param matchedBy EXACT (written for the same exception) or SEMANTIC (reads alike)
 * @param score 1.0 for an exact match, otherwise the similarity, 0-1
 * @param date for a past RCA, the day it is about
 * @param summary a runbook's mitigation, or a past RCA's root cause and resolution
 */
public record KnowledgeRef(String type, String id, String title, String matchedBy, double score, String date,
        String summary) {
}
