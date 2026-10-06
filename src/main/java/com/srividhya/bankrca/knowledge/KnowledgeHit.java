package com.srividhya.bankrca.knowledge;

import java.util.Map;

/**
 * A knowledge document found for a query.
 *
 * @param type runbook or past-rca
 * @param matchedBy EXACT (it is written for the same exception) or SEMANTIC (it reads alike)
 * @param score 1.0 for an exact match, otherwise the similarity of the best passage, 0-1
 * @param related true when the match is strong enough to act on: exact, or at or above the index's threshold
 * @param matchedPassage the part of the document that matched; null for an exact match
 * @param summary what to take from it: a runbook's mitigation, or a past RCA's root cause and resolution
 * @param metadata the "- Key: value" lines of the document
 */
public record KnowledgeHit(String id, String type, String title, String matchedBy, double score, boolean related,
        String matchedPassage, String summary, Map<String, String> metadata) {
}
