package com.srividhya.bankrca.assistant;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.srividhya.bankrca.security.PiiMasker;
import com.srividhya.bankrca.tools.ToolRegistry;

/**
 * Answers a question about the failures by letting a chat model call the RCA tools. The loop
 * is run here, not inside the model: the model is asked, any tool calls it makes are executed
 * by Spring AI's ToolCallingManager, the results go back to the model, and so on until it
 * answers in text or the round limit is reached. The final answer is checked against the tool
 * results before it goes out (AnswerGuard). Keeping the loop here means the same code
 * works with any ChatModel, and that every tool call is visible in the result.
 */
@Service
public class RcaAssistant {

    /**
     * @param model which model answered
     * @param toolCalls the tools the model called, in order, with the arguments it chose
     * @param complete false when the round limit was reached before the model gave a final answer
     * @param grounded false when the model's answer cited something no tool returned; the answer
     *        is then the plain one built from the tool results, and withheld lists what was made up
     */
    public record Answer(String answer, String model, List<ToolUse> toolCalls, boolean complete, boolean grounded,
            List<String> withheld) {
    }

    public record ToolUse(String tool, String arguments) {
    }

    private static final Logger log = LoggerFactory.getLogger(RcaAssistant.class);
    /** A question should not need more than a few rounds; a model that keeps calling tools is stopped. */
    private static final int MAX_ROUNDS = 4;
    private static final int MAX_QUESTION_CHARS = 1000;

    private final ChatModel model;
    private final ToolRegistry tools;
    private final PiiMasker masker;
    private final ToolCallingManager toolCalling = ToolCallingManager.builder().build();
    private final String systemPrompt;

    public RcaAssistant(ChatModel model, ToolRegistry tools, PiiMasker masker) {
        this.model = model;
        this.tools = tools;
        this.masker = masker;
        try {
            this.systemPrompt = new ClassPathResource("prompts/rca-assistant.md").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String modelName() {
        return model instanceof ScriptedChatModel ? ScriptedChatModel.NAME : model.getClass().getSimpleName();
    }

    public Answer ask(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("'question' is required");
        }
        if (question.length() > MAX_QUESTION_CHARS) {
            throw new IllegalArgumentException("'question' is too long: at most " + MAX_QUESTION_CHARS + " characters");
        }
        // The model executes nothing itself: it is told which tools exist, and its tool calls come back to this loop
        ToolCallingChatOptions options = (ToolCallingChatOptions) ToolCallingChatOptions.builder()
                .toolCallbacks(tools.callbacks()).build();
        String asked = masker.mask(question.strip());
        List<Message> messages = List.of(new SystemMessage(systemPrompt), new UserMessage(asked));
        List<ToolUse> used = new ArrayList<>();
        List<ToolResponse> results = new ArrayList<>();

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            Prompt prompt = new Prompt(messages, options);
            ChatResponse response = model.call(prompt);
            if (!response.hasToolCalls()) {
                return checked(response.getResult().getOutput().getText(), asked, used, results);
            }
            for (ToolCall call : response.getResult().getOutput().getToolCalls()) {
                used.add(new ToolUse(call.name(), call.arguments()));
            }
            ToolExecutionResult executed = toolCalling.executeToolCalls(prompt, response);
            messages = executed.conversationHistory();
            if (messages.get(messages.size() - 1) instanceof ToolResponseMessage answered) {
                results.addAll(answered.getResponses());
            }
        }
        log.warn("The model was still calling tools after {} rounds; stopped", MAX_ROUNDS);
        return new Answer("I could not finish answering: the question needed more tool calls than allowed.", modelName(),
                used, false, true, List.of());
    }

    /**
     * The model's answer goes out only if everything it cites came from a tool or the
     * question. Otherwise the plain answer built from the tool results is given instead:
     * less fluent, but true to what the tools returned.
     */
    private Answer checked(String modelAnswer, String question, List<ToolUse> used, List<ToolResponse> results) {
        List<String> ungrounded = AnswerGuard.ungrounded(modelAnswer, question,
                results.stream().map(ToolResponse::responseData).toList());
        if (ungrounded.isEmpty()) {
            return new Answer(masker.mask(modelAnswer), modelName(), used, true, true, List.of());
        }
        log.warn("The model's answer cited {} which no tool returned; replaced with the answer built from the tool results",
                ungrounded);
        String fallback = results.isEmpty()
                ? "I cannot check that answer against the tools, so I am not passing it on. Ask about the failures, "
                        + "a finding, a runbook or past RCAs."
                : ToolAnswerTemplates.render(results);
        return new Answer(masker.mask(fallback), modelName(), used, true, false, ungrounded);
    }
}
