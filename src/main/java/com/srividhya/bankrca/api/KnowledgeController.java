package com.srividhya.bankrca.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.srividhya.bankrca.knowledge.KnowledgeService;

/** Search the runbooks and past RCAs, in plain words or with an exception signature. */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private static final int MAX_QUERY_CHARS = 2000;

    private final KnowledgeService knowledge;

    public KnowledgeController(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @GetMapping
    public Map<String, Object> status() {
        return Map.of("search", knowledge.mode(), "runbooks", knowledge.count(KnowledgeService.RUNBOOK), "pastRcas",
                knowledge.count(KnowledgeService.PAST_RCA), "description", knowledge.description());
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam String q, @RequestParam(defaultValue = "runbook") String type,
            @RequestParam(defaultValue = "3") int limit) {
        if (q.isBlank() || q.length() > MAX_QUERY_CHARS) {
            return ResponseEntity.badRequest().body(Map.of("error", "'q' is required, at most " + MAX_QUERY_CHARS + " characters"));
        }
        if (!List.of(KnowledgeService.RUNBOOK, KnowledgeService.PAST_RCA).contains(type)) {
            return ResponseEntity.badRequest().body(Map.of("error", "'type' must be runbook or past-rca"));
        }
        return ResponseEntity.ok(knowledge.search(q, type, Math.max(1, Math.min(limit, 10)), null));
    }

    /** Reads the knowledge folder again, after files were added or edited. */
    @PostMapping("/reload")
    public Map<String, Object> reload() {
        knowledge.reload();
        return status();
    }
}
