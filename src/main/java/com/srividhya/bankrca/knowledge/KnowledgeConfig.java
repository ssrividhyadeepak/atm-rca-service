package com.srividhya.bankrca.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the retrieval engine. With rca.knowledge.mode=embedding (the default) it loads the
 * local embedding model, which is downloaded once into the cache folder; if that fails - no
 * download access, an unsupported platform - the service still starts, on keyword search,
 * and says so. That is the deterministic fallback for retrieval.
 */
@Configuration
public class KnowledgeConfig {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeConfig.class);
    private static final String MODEL = "all-MiniLM-L6-v2";

    @Bean
    KnowledgeIndex knowledgeIndex(@Value("${rca.knowledge.mode}") String mode,
            @Value("${rca.knowledge.model-cache-dir}") String cacheDir) {
        if (!"embedding".equalsIgnoreCase(mode)) {
            return new KeywordKnowledgeIndex();
        }
        try {
            TransformersEmbeddingModel model = new TransformersEmbeddingModel();
            model.setResourceCacheDirectory(cacheDir);
            model.afterPropertiesSet();
            // One real call, so a broken native library shows up now and not on the first search
            model.embed("warm up");
            return new EmbeddingKnowledgeIndex(model, MODEL);
        } catch (Throwable e) {
            log.warn("The embedding model could not be loaded ({}: {}). Falling back to keyword search; set "
                    + "rca.knowledge.mode=keyword to silence this", e.getClass().getSimpleName(), e.getMessage());
            return new KeywordKnowledgeIndex();
        }
    }
}
