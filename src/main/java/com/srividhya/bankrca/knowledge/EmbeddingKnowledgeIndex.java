package com.srividhya.bankrca.knowledge;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;

/**
 * Semantic search: each passage is turned into a vector by a local embedding model and kept
 * in Spring AI's in-memory SimpleVectorStore; a query is embedded the same way and compared
 * by cosine similarity. Finds passages that mean the same thing in other words. Swapping
 * SimpleVectorStore for a database-backed VectorStore would not change this class's callers.
 */
public class EmbeddingKnowledgeIndex implements KnowledgeIndex {

    private final EmbeddingModel model;
    private final String modelName;
    private SimpleVectorStore store;

    public EmbeddingKnowledgeIndex(EmbeddingModel model, String modelName) {
        this.model = model;
        this.modelName = modelName;
        this.store = SimpleVectorStore.builder(model).build();
    }

    @Override
    public String mode() {
        return "embedding";
    }

    @Override
    public String description() {
        return "embeddings (" + modelName + ", local) in an in-memory vector store";
    }

    /**
     * Calibrated on evals/knowledge-cases.json (see KnowledgeEvalTest): right matches score
     * 0.52 and up, wrong ones up to 0.50. The gap is narrow, so re-check it when documents change.
     */
    @Override
    public double relatedThreshold() {
        return 0.51;
    }

    @Override
    public synchronized void put(List<Passage> passages) {
        if (passages.isEmpty()) {
            return;
        }
        List<Document> docs = passages.stream()
                .map(p -> Document.builder().id(uuid(p.id())).text(p.text())
                        .metadata(Map.of("passageId", p.id(), "docId", p.docId(), "type", p.type())).build())
                .toList();
        store.delete(docs.stream().map(Document::getId).toList());
        store.add(docs);
    }

    @Override
    public synchronized void clear() {
        store = SimpleVectorStore.builder(model).build();
    }

    @Override
    public synchronized List<Scored> search(String query, String type, int topK) {
        SearchRequest request = SearchRequest.builder().query(query).topK(topK).similarityThresholdAll()
                .filterExpression("type == '" + type.replace("'", "") + "'").build();
        return store.similaritySearch(request).stream()
                .map(d -> new Scored(new Passage((String) d.getMetadata().get("passageId"),
                        (String) d.getMetadata().get("docId"), type, d.getText()),
                        Math.round(d.getScore() * 1000) / 1000.0))
                .toList();
    }

    private static String uuid(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
