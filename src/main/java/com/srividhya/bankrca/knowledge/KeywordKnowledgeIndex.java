package com.srividhya.bankrca.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keyword search, with no model: the fallback when the embedding model cannot be loaded
 * (no download access), and the mode tests use. A passage scores by how much of the query's
 * weight it covers, where rare words weigh more than common ones. It only finds passages that
 * share words with the query - it cannot tell that "refused" and "declined" mean the same.
 */
public class KeywordKnowledgeIndex implements KnowledgeIndex {

    private static final Set<String> STOP_WORDS = Set.of("the", "a", "an", "and", "or", "of", "to", "in", "on", "at",
            "is", "are", "was", "were", "be", "with", "for", "from", "by", "it", "its", "this", "that", "as", "not",
            "no", "after", "n", "id", "exception", "attached", "com", "java", "lang", "org", "example", "bank");

    private final Map<String, Passage> passages = new LinkedHashMap<>();
    private final Map<String, Set<String>> words = new HashMap<>();

    @Override
    public String mode() {
        return "keyword";
    }

    @Override
    public String description() {
        return "keyword matching (no embedding model)";
    }

    @Override
    public double relatedThreshold() {
        return 0.45;
    }

    @Override
    public synchronized void put(List<Passage> added) {
        for (Passage p : added) {
            passages.put(p.id(), p);
            words.put(p.id(), new HashSet<>(tokens(p.text())));
        }
    }

    @Override
    public synchronized void clear() {
        passages.clear();
        words.clear();
    }

    @Override
    public synchronized List<Scored> search(String query, String type, int topK) {
        List<Passage> candidates = passages.values().stream().filter(p -> p.type().equals(type)).toList();
        Set<String> queryWords = new HashSet<>(tokens(query));
        if (candidates.isEmpty() || queryWords.isEmpty()) {
            return List.of();
        }
        // A word found in few passages says more about which passage is meant
        Map<String, Double> weight = new HashMap<>();
        double total = 0;
        for (String w : queryWords) {
            long containing = candidates.stream().filter(p -> words.get(p.id()).contains(w)).count();
            double idf = Math.log(1 + (candidates.size() - containing + 0.5) / (containing + 0.5));
            weight.put(w, idf);
            total += idf;
        }
        List<Scored> scored = new ArrayList<>();
        for (Passage p : candidates) {
            double covered = 0;
            for (String w : queryWords) {
                if (words.get(p.id()).contains(w)) {
                    covered += weight.get(w);
                }
            }
            if (covered > 0) {
                scored.add(new Scored(p, Math.round(covered / total * 1000) / 1000.0));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparing(s -> s.passage().id()));
        return scored.subList(0, Math.min(topK, scored.size()));
    }

    /** Lower-case words; camel case is split, so HostAuthTimeoutException also matches "timeout". */
    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase().split("[^a-z0-9]+")) {
            String w = raw.length() > 4 && raw.endsWith("s") ? raw.substring(0, raw.length() - 1) : raw;
            if (w.length() > 1 && !STOP_WORDS.contains(w) && !w.matches("\\d+")) {
                out.add(w);
            }
        }
        return out;
    }
}
