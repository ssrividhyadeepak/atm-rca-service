package com.srividhya.bankrca.knowledge;

import java.util.List;

/**
 * The retrieval engine under the knowledge base: holds passages and finds the ones closest
 * to a query. Two implementations: embeddings in a vector store, and keyword matching as the
 * fallback when the embedding model cannot be loaded.
 */
public interface KnowledgeIndex {

    /** One piece of a document, small enough to be about one thing. */
    record Passage(String id, String docId, String type, String text) {
    }

    /** score is 0-1; what counts as a good score depends on the implementation, see relatedThreshold. */
    record Scored(Passage passage, double score) {
    }

    /** "embedding" or "keyword". */
    String mode();

    String description();

    /** A score at or above this means the passage is probably about the same thing as the query. */
    double relatedThreshold();

    /** Adds passages, replacing any with the same id. */
    void put(List<Passage> passages);

    void clear();

    /** Best first. */
    List<Scored> search(String query, String type, int topK);
}
