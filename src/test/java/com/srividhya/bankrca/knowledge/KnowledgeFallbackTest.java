package com.srividhya.bankrca.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KnowledgeFallbackTest {

    @Test
    void keywordModeNeedsNoModel() {
        assertThat(new KnowledgeConfig().knowledgeIndex("keyword", "ignored").mode()).isEqualTo("keyword");
    }

    @Test
    void fallsBackToKeywordSearchWhenTheModelCannotBeLoaded() {
        // A cache folder that cannot exist: /dev/null is a file, so nothing can be created under it
        KnowledgeIndex index = new KnowledgeConfig().knowledgeIndex("embedding", "/dev/null/model-cache");

        assertThat(index.mode()).isEqualTo("keyword");
        assertThat(index.description()).isEqualTo("keyword matching (no embedding model)");
    }
}
