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

import com.srividhya.bankrca.incident.IncidentDraft;
import com.srividhya.bankrca.incident.IncidentService;
import com.srividhya.bankrca.incident.IncidentService.DraftResult;
import com.srividhya.bankrca.incident.IncidentService.NotFoundException;
import com.srividhya.bankrca.security.Caller.DeniedException;

/**
 * The incident workflow: draft one from a finding, look at it, then approve or reject it.
 * Approving is what sends it to the ticket system.
 */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    public record DraftRequest(Integer rank, String note) {
    }

    public record Decision(String comment, String reason) {
    }

    private final IncidentService incidents;

    public IncidentController(IncidentService incidents) {
        this.incidents = incidents;
    }

    @PostMapping("/drafts")
    public ResponseEntity<DraftResult> draft(@RequestBody DraftRequest request) {
        DraftResult result = incidents.draft(request.rank(), request.note());
        return ResponseEntity.status(result.created() ? 201 : 200).body(result);
    }

    @GetMapping
    public List<IncidentDraft> latest(@RequestParam(defaultValue = "20") int limit) {
        return incidents.latest(Math.max(1, Math.min(limit, 100)));
    }

    @GetMapping("/{id}")
    public IncidentDraft get(@PathVariable String id) {
        return incidents.get(id);
    }

    @PostMapping("/{id}/approve")
    public IncidentDraft approve(@PathVariable String id, @RequestBody(required = false) Decision decision) {
        return incidents.approve(id, decision == null ? null : decision.comment());
    }

    @PostMapping("/{id}/reject")
    public IncidentDraft reject(@PathVariable String id, @RequestBody(required = false) Decision decision) {
        return incidents.reject(id, decision == null ? null : decision.reason());
    }

    /** For a draft that was approved but could not be sent: try again. */
    @PostMapping("/{id}/submit")
    public IncidentDraft submit(@PathVariable String id) {
        return incidents.retrySubmit(id);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }

    /** The draft is not in a state that allows this. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(DeniedException.class)
    public ResponseEntity<Map<String, String>> denied(DeniedException e) {
        return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
    }
}
