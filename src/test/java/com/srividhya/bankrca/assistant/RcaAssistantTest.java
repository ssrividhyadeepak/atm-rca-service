package com.srividhya.bankrca.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.srividhya.bankrca.assistant.RcaAssistant.Answer;
import com.srividhya.bankrca.monitor.MonitoringService;
import com.srividhya.bankrca.security.PiiMasker;
import com.srividhya.bankrca.tools.ToolRegistry;

/**
 * The tool-calling loop with models written for the test, so each behaviour a real model
 * could show - a wrong call it corrects, never stopping, sensitive text in the question - is
 * exercised without one.
 */
@SpringBootTest
@ActiveProfiles("test")
class RcaAssistantTest {

    @Autowired
    ToolRegistry tools;

    @Autowired
    MonitoringService monitoring;

    private static ChatResponse toolCall(String name, String arguments) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(new ToolCall("id-" + name, "function", name, arguments))).build())));
    }

    private static ChatResponse text(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    void givesTheModelTheSystemPromptTheMaskedQuestionAndTheToolDefinitions() {
        monitoring.run("MANUAL");
        List<Message> seen = new ArrayList<>();
        List<String> offered = new ArrayList<>();
        ChatModel model = prompt -> {
            seen.addAll(prompt.getInstructions());
            ((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks()
                    .forEach(c -> offered.add(c.getToolDefinition().name()));
            return text("ok");
        };

        Answer answer = new RcaAssistant(model, tools, new PiiMasker()).ask("Why was card 4111111111111111 refused?");

        assertThat(answer.answer()).isEqualTo("ok");
        assertThat(answer.toolCalls()).isEmpty();
        assertThat(seen.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(seen.get(0).getText()).contains("Everything you know about the failures comes from the tools")
                .contains("NO_MATCH");
        assertThat(seen.get(1)).isInstanceOf(UserMessage.class);
        assertThat(seen.get(1).getText()).isEqualTo("Why was card ************1111 refused?");
        assertThat(offered).containsExactly("getFailureSummary", "getFinding", "lookupRunbook", "searchHistoricalRca");
    }

    @Test
    void aRefusedToolCallGoesBackToTheModelSoItCanCorrectItself() {
        monitoring.run("MANUAL");
        List<String> toolResults = new ArrayList<>();
        AtomicInteger round = new AtomicInteger();
        ChatModel model = prompt -> {
            Message last = prompt.getInstructions().get(prompt.getInstructions().size() - 1);
            if (last instanceof ToolResponseMessage tool) {
                toolResults.add(tool.getResponses().get(0).responseData());
            }
            return switch (round.incrementAndGet()) {
                case 1 -> toolCall("getFinding", "{\"rank\":99}");
                case 2 -> toolCall("getFinding", "{\"rank\":1}");
                default -> text("done");
            };
        };

        Answer answer = new RcaAssistant(model, tools, new PiiMasker()).ask("Tell me about the last finding");

        assertThat(answer.complete()).isTrue();
        assertThat(answer.toolCalls()).extracting(RcaAssistant.ToolUse::arguments)
                .containsExactly("{\"rank\":99}", "{\"rank\":1}");
        // The refusal reached the model as the tool's answer, with what to send instead...
        assertThat(toolResults.get(0)).contains("'rank' must be between 1 and 8");
        // ...and the corrected call returned the finding
        assertThat(toolResults.get(1)).contains("\"rank\":1").contains("HostAuthTimeoutException");
    }

    @Test
    void stopsAModelThatNeverStopsCallingTools() {
        monitoring.run("MANUAL");
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = prompt -> {
            calls.incrementAndGet();
            return toolCall("getFailureSummary", "{}");
        };

        Answer answer = new RcaAssistant(model, tools, new PiiMasker()).ask("What failed?");

        assertThat(answer.complete()).isFalse();
        assertThat(calls).hasValue(4);
        assertThat(answer.toolCalls()).hasSize(4);
        assertThat(answer.answer()).contains("could not finish");
    }

    @Test
    void anAnswerThatCitesWhatNoToolReturnedIsReplacedByThePlainOne() {
        monitoring.run("MANUAL");
        AtomicInteger round = new AtomicInteger();
        ChatModel inventive = prompt -> round.incrementAndGet() == 1
                ? toolCall("lookupRunbook", "{\"query\":\"HostAuthTimeoutException\",\"limit\":1}")
                : text("Follow runbook RB-042 and revert commit deadbeef12 in HostAuthClientImpl.java:99; "
                        + "the cause is a KafkaTimeoutException.");

        Answer answer = new RcaAssistant(inventive, tools, new PiiMasker()).ask("How do I fix HostAuthTimeoutException?");

        assertThat(answer.grounded()).isFalse();
        assertThat(answer.withheld()).containsExactlyInAnyOrder("RB-042", "deadbeef12", "HostAuthClientImpl.java:99",
                "KafkaTimeoutException");
        // What goes out is built from the tool result, not from the model's text
        assertThat(answer.answer()).startsWith("Runbook RB-001, \"Host authorization timeouts on withdrawals\":")
                .doesNotContain("RB-042").doesNotContain("deadbeef12");
        assertThat(answer.complete()).isTrue();
    }

    @Test
    void anAnswerThatCitesOnlyWhatTheToolsReturnedGoesOutAsWritten() {
        monitoring.run("MANUAL");
        AtomicInteger round = new AtomicInteger();
        String written = "Start with runbook RB-001: HostAuthTimeoutException usually means the timeout setting was lowered.";
        ChatModel careful = prompt -> round.incrementAndGet() == 1
                ? toolCall("lookupRunbook", "{\"query\":\"HostAuthTimeoutException\",\"limit\":1}") : text(written);

        Answer answer = new RcaAssistant(careful, tools, new PiiMasker()).ask("How do I fix HostAuthTimeoutException?");

        assertThat(answer.grounded()).isTrue();
        assertThat(answer.withheld()).isEmpty();
        assertThat(answer.answer()).isEqualTo(written);
    }

    @Test
    void anAnswerWithNoToolBehindItIsNotPassedOnWhenItCitesSomething() {
        ChatModel fromMemory = prompt -> text("That is the well-known NullPointerException in Ledger.java:10; see RCA-2020-01-01.");

        Answer answer = new RcaAssistant(fromMemory, tools, new PiiMasker()).ask("What is wrong?");

        assertThat(answer.grounded()).isFalse();
        assertThat(answer.answer()).startsWith("I cannot check that answer against the tools");
        assertThat(answer.withheld()).contains("RCA-2020-01-01", "Ledger.java:10", "NullPointerException");
        // An exception named in the question itself may be repeated
        assertThat(new RcaAssistant(prompt -> text("NullPointerException means a null was dereferenced."), tools,
                new PiiMasker()).ask("What is a NullPointerException?").grounded()).isTrue();
    }

    @Test
    void theScriptedModelPicksToolsByKeywordsAndReportsAToolThatCouldNotAnswer() {
        monitoring.run("MANUAL");
        RcaAssistant assistant = new RcaAssistant(new ScriptedChatModel(), tools, new PiiMasker());

        assertThat(assistant.ask("tell me about finding #2").toolCalls()).extracting(RcaAssistant.ToolUse::tool)
                .containsExactly("getFinding");
        assertThat(assistant.ask("anything similar in the past for NullPointerException?").toolCalls())
                .extracting(RcaAssistant.ToolUse::arguments).containsExactly("{\"query\":\"NullPointerException\",\"limit\":2}");
        assertThat(assistant.ask("status").toolCalls()).extracting(RcaAssistant.ToolUse::tool)
                .containsExactly("getFailureSummary");
        assertThat(assistant.ask("show finding 42").answer())
                .startsWith("getFinding could not answer:").contains("'rank' must be between 1 and 8");
        assertThat(assistant.modelName()).isEqualTo("scripted (no LLM configured)");
    }
}
