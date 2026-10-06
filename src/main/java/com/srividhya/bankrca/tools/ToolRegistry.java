package com.srividhya.bankrca.tools;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tools as a model sees them: each with a name, a description and a JSON Schema for its
 * arguments, generated from the method signature, and callable by name with JSON arguments.
 * The assistant and the tool endpoint both go through here, and an MCP server can too.
 */
@Component
public class ToolRegistry {

    /**
     * @param inputSchema JSON Schema of the arguments
     * @param outputSchema JSON Schema of the result
     */
    public record ToolInfo(String name, String description, JsonNode inputSchema, JsonNode outputSchema,
            ToolAudit.Stats stats) {
    }

    /** The tool exists but refused the arguments: the message says what to send instead. */
    public static class ToolRejectedException extends RuntimeException {
        public ToolRejectedException(String message) {
            super(message);
        }
    }

    public static class UnknownToolException extends RuntimeException {
        public UnknownToolException(String message) {
            super(message);
        }
    }

    private final List<ToolCallback> callbacks;
    private final RcaTools tools;
    private final ToolAudit audit;
    private final JsonMapper json = new JsonMapper();

    public ToolRegistry(RcaTools tools, ToolAudit audit) {
        this.tools = tools;
        this.audit = audit;
        this.callbacks = Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks())
                .sorted(Comparator.comparing((ToolCallback c) -> c.getToolDefinition().name())).toList();
    }

    public List<ToolCallback> callbacks() {
        return callbacks;
    }

    public List<ToolInfo> list() {
        List<ToolInfo> infos = new ArrayList<>();
        for (ToolCallback c : callbacks) {
            String name = c.getToolDefinition().name();
            infos.add(new ToolInfo(name, c.getToolDefinition().description().replaceAll("\\s+", " ").strip(),
                    json.readTree(c.getToolDefinition().inputSchema()), outputSchema(name), audit.stats(name)));
        }
        return infos;
    }

    /**
     * @param argumentsJson a JSON object with the tool's arguments
     * @return the tool's result as JSON
     * @throws UnknownToolException when there is no such tool
     * @throws ToolRejectedException when the arguments are not acceptable
     */
    public String invoke(String name, String argumentsJson) {
        ToolCallback callback = callbacks.stream().filter(c -> c.getToolDefinition().name().equals(name)).findFirst()
                .orElseThrow(() -> new UnknownToolException("There is no tool '" + name + "'. Tools: "
                        + callbacks.stream().map(c -> c.getToolDefinition().name()).toList()));
        String arguments = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
        try {
            if (!json.readTree(arguments).isObject()) {
                throw new ToolRejectedException("The arguments must be a JSON object, e.g. {\"query\": \"...\"}");
            }
        } catch (JacksonException e) {
            throw new ToolRejectedException("The arguments are not valid JSON");
        }
        try {
            return callback.call(arguments);
        } catch (RuntimeException e) {
            // A refusal by the tool, or arguments of the wrong type that never reached it
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof IllegalArgumentException && t.getMessage() != null && t.getMessage().startsWith("'")) {
                    throw new ToolRejectedException(t.getMessage());
                }
                if (t instanceof JacksonException || t instanceof NumberFormatException || t instanceof ClassCastException) {
                    throw new ToolRejectedException("The arguments do not fit the tool's input schema; see GET /api/tools");
                }
            }
            if (e instanceof ToolExecutionException && e.getCause() instanceof IllegalArgumentException refused) {
                throw new ToolRejectedException(refused.getMessage());
            }
            throw e;
        }
    }

    private JsonNode outputSchema(String toolName) {
        for (Method m : tools.getClass().getMethods()) {
            Tool tool = m.getAnnotation(Tool.class);
            if (tool != null && tool.name().equals(toolName)) {
                return json.readTree(JsonSchemaGenerator.generateForType(m.getGenericReturnType()));
            }
        }
        return null;
    }
}
