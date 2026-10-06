package com.srividhya.bankrca.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.srividhya.bankrca.assistant.RcaAssistant;
import com.srividhya.bankrca.assistant.RcaAssistant.Answer;

/** Ask a question about the failures; the answer comes with the tool calls that produced it. */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    public record Question(String question) {
    }

    private final RcaAssistant assistant;

    public AssistantController(RcaAssistant assistant) {
        this.assistant = assistant;
    }

    @PostMapping("/ask")
    public Answer ask(@RequestBody Question question) {
        return assistant.ask(question.question());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badQuestion(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
