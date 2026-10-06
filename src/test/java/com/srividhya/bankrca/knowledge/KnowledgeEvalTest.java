package com.srividhya.bankrca.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.srividhya.bankrca.security.PiiMasker;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Retrieval eval: for each case in evals/knowledge-cases.json, is the right document on top,
 * found the expected way (exact or semantic), and is "nothing matches" recognised? Run for
 * both retrieval engines. If retrieval hands the analyzer the wrong runbook, nothing later
 * can put that right, so this is a regression gate for the documents, the model and the thresholds.
 */
class KnowledgeEvalTest {

    private static final Path KNOWLEDGE = Path.of("config/knowledge");

    @Test
    void keywordSearch() {
        // Word matching cannot do paraphrases well: the bar is what it can be relied on for
        assertThat(run(new KeywordKnowledgeIndex())).isGreaterThanOrEqualTo(0.7);
    }

    @Test
    void embeddingSearch() {
        KnowledgeIndex index = new KnowledgeConfig().knowledgeIndex("embedding", ".model-cache");
        Assumptions.assumeTrue(index.mode().equals("embedding"), "the embedding model could not be loaded on this machine");

        assertThat(run(index)).isGreaterThanOrEqualTo(0.9);
    }

    private static double run(KnowledgeIndex index) {
        KnowledgeService knowledge = new KnowledgeService(index, new PiiMasker(), KNOWLEDGE);
        knowledge.reload();
        JsonNode cases = new JsonMapper().readTree(Path.of("evals/knowledge-cases.json").toFile());
        List<String> rows = new ArrayList<>();
        int passed = 0;
        for (JsonNode c : cases) {
            List<KnowledgeHit> hits = knowledge.search(c.get("query").asString(), c.get("type").asString(), 3, null);
            KnowledgeHit top = hits.isEmpty() ? null : hits.get(0);
            boolean ok;
            if (c.get("expect").isNull()) {
                // Nothing should be offered as a match
                ok = top == null || !top.related();
            } else {
                ok = top != null && top.related() && top.id().equals(c.get("expect").asString())
                        && top.matchedBy().equals(c.get("matchedBy").asString());
            }
            passed += ok ? 1 : 0;
            rows.add("%-4s %s %-9s %-15s %.3f  want %-15s %s".formatted(c.get("id").asString(), ok ? "PASS" : "FAIL",
                    top == null ? "-" : top.matchedBy() + (top.related() ? "" : "?"), top == null ? "-" : top.id(),
                    top == null ? 0 : top.score(), c.get("expect").isNull() ? "(none)" : c.get("expect").asString(),
                    c.get("note").asString()));
        }
        double score = (double) passed / cases.size();
        System.out.println("\n===== Knowledge retrieval eval: " + index.mode() + " =====");
        rows.forEach(System.out::println);
        System.out.printf("SCORE: %d/%d (%.0f%%)%n%n", passed, cases.size(), score * 100);
        return score;
    }
}
