package com.srividhya.bankrca.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * Prompts offered to MCP clients, where they show up as ready-made commands. The text is a
 * file in the code base (prompts/daily-rca.md), reviewed and released like code; the version
 * is part of the description a client sees.
 */
@Component
public class RcaPrompts {

    static final String DAILY_RCA_VERSION = "v1";

    private final String dailyRca;

    public RcaPrompts() {
        try {
            this.dailyRca = new ClassPathResource("prompts/daily-rca.md").getContentAsString(StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @McpPrompt(name = "daily_rca", title = "Daily failure briefing",
            description = "Brief the on-call engineer on today's production failures, from the RCA tools")
    public GetPromptResult dailyRca() {
        return new GetPromptResult("Daily failure briefing (daily-rca/" + DAILY_RCA_VERSION + ")",
                List.of(new PromptMessage(Role.USER, new TextContent(dailyRca))));
    }
}
