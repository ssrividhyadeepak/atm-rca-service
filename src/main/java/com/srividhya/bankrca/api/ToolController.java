package com.srividhya.bankrca.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.srividhya.bankrca.security.Caller.DeniedException;
import com.srividhya.bankrca.security.Caller.RateLimitedException;
import com.srividhya.bankrca.tools.ToolRegistry;
import com.srividhya.bankrca.tools.ToolRegistry.ToolInfo;
import com.srividhya.bankrca.tools.ToolRegistry.ToolRejectedException;
import com.srividhya.bankrca.tools.ToolRegistry.UnknownToolException;

/**
 * The tools from the outside: list them with their schemas, or call one by name with JSON
 * arguments - what a model does, done by hand.
 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolRegistry registry;

    public ToolController(ToolRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<ToolInfo> list() {
        return registry.list();
    }

    @PostMapping(value = "/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
    public String call(@PathVariable String name, @RequestBody(required = false) String arguments) {
        return registry.invoke(name, arguments);
    }

    @ExceptionHandler(UnknownToolException.class)
    public ResponseEntity<Map<String, String>> unknown(UnknownToolException e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ToolRejectedException.class)
    public ResponseEntity<Map<String, String>> rejected(ToolRejectedException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(DeniedException.class)
    public ResponseEntity<Map<String, String>> denied(DeniedException e) {
        return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<Map<String, String>> rateLimited(RateLimitedException e) {
        return ResponseEntity.status(429).header("Retry-After", String.valueOf(e.retryAfterSeconds()))
                .body(Map.of("error", e.getMessage()));
    }

    /** Anything else is the tool's own failure: logged with the call id by ToolAudit, not echoed to the caller. */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> failed(RuntimeException e) {
        return ResponseEntity.status(500).body(Map.of("error", "The tool failed. See logs/audit.log for the call."));
    }
}
