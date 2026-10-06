package com.srividhya.bankrca.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for an LLM where none is available. It behaves like a chat model at the protocol
 * level - asked a question it answers with tool calls, given the tool results it answers
 * with text - but it chooses tools by keywords and builds its answer from fixed templates.
 * It proves the tool-calling loop works end to end; it understands nothing. A real ChatModel
 * bean (any Spring AI model starter) replaces it without other changes.
 */
public class ScriptedChatModel implements ChatModel {

    public static final String NAME = "scripted (no LLM configured)";

    private static final Pattern FINDING_NUMBER = Pattern.compile("(?i)(?:finding|rank|number|#)\\s*#?\\s*(\\d+)");
    private static final Pattern EXCEPTION_CLASS = Pattern.compile("\\b([A-Z]\\w*(?:Exception|Error))\\b");
    private static final List<String> RUNBOOK_WORDS = List.of("runbook", "how do i fix", "how to fix", "mitigat",
            "what should i do", "what do i do", "steps");
    private static final List<String> HISTORY_WORDS = List.of("before", "past", "histor", "previous", "seen",
            "last time", "similar", "happened");

    private final JsonMapper json = new JsonMapper();

    @Override
    public ChatResponse call(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        String question = "";
        List<ToolResponse> results = new ArrayList<>();
        for (Message m : messages) {
            if (m instanceof UserMessage user) {
                question = user.getText();
                results.clear();
            } else if (m instanceof ToolResponseMessage tool) {
                results.addAll(tool.getResponses());
            }
        }
        AssistantMessage reply = results.isEmpty()
                ? AssistantMessage.builder().content("").toolCalls(plan(question)).build()
                : new AssistantMessage(ToolAnswerTemplates.render(results));
        return new ChatResponse(List.of(new Generation(reply)));
    }

    /** Which tools to call for a question: by keywords, the way a model would by understanding it. */
    private List<ToolCall> plan(String question) {
        String q = question.toLowerCase();
        List<ToolCall> calls = new ArrayList<>();
        Matcher finding = FINDING_NUMBER.matcher(question);
        if (finding.find()) {
            calls.add(call("getFinding", "{\"rank\":" + finding.group(1) + "}"));
        }
        // An exception name makes the better query: it gives an exact match
        Matcher exception = EXCEPTION_CLASS.matcher(question);
        String query = json.writeValueAsString(exception.find() ? exception.group(1) : question);
        if (RUNBOOK_WORDS.stream().anyMatch(q::contains)) {
            calls.add(call("lookupRunbook", "{\"query\":" + query + ",\"limit\":1}"));
        }
        if (HISTORY_WORDS.stream().anyMatch(q::contains)) {
            calls.add(call("searchHistoricalRca", "{\"query\":" + query + ",\"limit\":2}"));
        }
        if (calls.isEmpty()) {
            calls.add(call("getFailureSummary", "{}"));
        }
        return calls;
    }

    private static ToolCall call(String name, String arguments) {
        return new ToolCall("call-" + name, "function", name, arguments);
    }
}
