package com.srividhya.bankrca.assistant;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AssistantConfig {

    /**
     * Used only when no real model is configured. Adding a Spring AI model starter (for
     * example for Azure OpenAI or Bedrock) with its settings provides a ChatModel bean, and
     * this one steps aside.
     */
    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    ChatModel scriptedChatModel() {
        return new ScriptedChatModel();
    }
}
