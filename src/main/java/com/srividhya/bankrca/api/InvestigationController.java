package com.srividhya.bankrca.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.srividhya.bankrca.investigation.Investigation;
import com.srividhya.bankrca.investigation.InvestigationService;
import com.srividhya.bankrca.investigation.InvestigationService.FixPlanInput;
import com.srividhya.bankrca.investigation.InvestigationService.HypothesisInput;
import com.srividhya.bankrca.investigation.InvestigationService.NotFoundException;
import com.srividhya.bankrca.security.Caller.DeniedException;

/** Investigations: the evidence gathered for a finding and the hypotheses recorded against it. */
@RestController
@RequestMapping("/api/investigations")
public class InvestigationController {

    public record StartRequest(Integer rank) {
    }

    public record HypothesesRequest(List<HypothesisInput> hypotheses) {
    }

    public record Reason(String reason) {
    }

    public record Note(String note) {
    }

    public record FixPlanRequest(Integer hypothesisRank, FixPlanInput plan) {
    }

    private final InvestigationService investigations;

    public InvestigationController(InvestigationService investigations) {
        this.investigations = investigations;
    }

    @PostMapping
    public ResponseEntity<Investigation> start(@RequestBody StartRequest request) {
        return ResponseEntity.status(201).body(investigations.collect(request.rank()));
    }

    @GetMapping
    public List<Investigation> latest(@RequestParam(defaultValue = "20") int limit) {
        return investigations.latest(Math.max(1, Math.min(limit, 100)));
    }

    @GetMapping("/{id}")
    public Investigation get(@PathVariable String id) {
        return investigations.get(id);
    }

    @PostMapping("/{id}/hypotheses")
    public Investigation hypotheses(@PathVariable String id, @RequestBody HypothesesRequest request) {
        return investigations.recordHypotheses(id, request.hypotheses());
    }

    @PostMapping("/{id}/evidence/{evidenceId}/exclude")
    public Investigation exclude(@PathVariable String id, @PathVariable String evidenceId, @RequestBody Reason reason) {
        return investigations.excludeEvidence(id, evidenceId, true, reason.reason());
    }

    @PostMapping("/{id}/evidence/{evidenceId}/restore")
    public Investigation restore(@PathVariable String id, @PathVariable String evidenceId) {
        return investigations.excludeEvidence(id, evidenceId, false, null);
    }

    @PostMapping("/{id}/notes")
    public Investigation note(@PathVariable String id, @RequestBody Note note) {
        return investigations.addNote(id, note.note());
    }

    @PostMapping("/{id}/fix-plan")
    public Investigation fixPlan(@PathVariable String id, @RequestBody FixPlanRequest request) {
        return investigations.recordFixPlan(id, request.hypothesisRank(), request.plan());
    }

    /** A person's decision: there is no tool for it. */
    @PostMapping("/{id}/fix-plan/approve")
    public Investigation approve(@PathVariable String id, @RequestBody(required = false) Reason comment) {
        return investigations.decideFixPlan(id, true, comment == null ? null : comment.reason());
    }

    @PostMapping("/{id}/fix-plan/reject")
    public Investigation reject(@PathVariable String id, @RequestBody(required = false) Reason reason) {
        return investigations.decideFixPlan(id, false, reason == null ? null : reason.reason());
    }

    @PostMapping("/{id}/pull-request")
    public Investigation pullRequest(@PathVariable String id) {
        return investigations.draftPullRequest(id);
    }

    @ExceptionHandler(DeniedException.class)
    public ResponseEntity<Map<String, String>> denied(DeniedException e) {
        return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> unavailable(IllegalStateException e) {
        return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }
}
