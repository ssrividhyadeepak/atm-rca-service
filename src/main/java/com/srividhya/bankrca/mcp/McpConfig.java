package com.srividhya.bankrca.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.srividhya.bankrca.tools.ToolRegistry;

/**
 * Publishes the RCA tools over MCP. They are the same tool objects the assistant and the
 * /api/tools endpoint use, so an MCP call goes through the same validation, scope check,
 * rate limit and audit line as any other call.
 */
@Configuration
public class McpConfig {

    @Bean
    ToolCallbackProvider rcaToolCallbacks(ToolRegistry registry) {
        return ToolCallbackProvider.from(registry.callbacks());
    }
}
