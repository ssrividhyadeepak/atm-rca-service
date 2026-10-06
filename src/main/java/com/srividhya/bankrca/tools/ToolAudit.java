package com.srividhya.bankrca.tools;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.srividhya.bankrca.security.Caller;
import com.srividhya.bankrca.security.Caller.DeniedException;
import com.srividhya.bankrca.security.Caller.RateLimitedException;
import com.srividhya.bankrca.security.PiiMasker;
import com.srividhya.bankrca.security.TraceIdFilter;

import tools.jackson.databind.json.JsonMapper;

/**
 * Observability for tool calls. Every call - whoever makes it: the assistant, the tool
 * endpoint, later an MCP client - is given a call id, timed, counted, and written as one JSON
 * line to logs/audit.log with its masked arguments and outcome. Refused calls are recorded too.
 */
@Component
public class ToolAudit {

    /** Running totals for one tool since the service started; rejected includes denied and rate limited calls. */
    public record Stats(long calls, long ok, long rejected, long errors, long averageMs) {
    }

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");
    private static final Logger log = LoggerFactory.getLogger(ToolAudit.class);

    private static final class Counter {
        long calls;
        long ok;
        long rejected;
        long errors;
        long totalMs;
    }

    private final JsonMapper json = new JsonMapper();
    private final PiiMasker masker;
    private final Caller caller;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public ToolAudit(PiiMasker masker, Caller caller) {
        this.masker = masker;
        this.caller = caller;
    }

    /**
     * @param args the arguments as received; values are masked before they are written
     * @param requiredScopes what the caller's token must carry to call this tool
     * @throws IllegalArgumentException from the body, for arguments the tool refuses: recorded as REJECTED
     * @throws DeniedException when the caller lacks a scope: recorded as DENIED
     * @throws RateLimitedException when the caller is over its allowance: recorded as RATE_LIMITED
     */
    public <T> T run(String tool, Set<String> requiredScopes, Map<String, ?> args, Supplier<T> body) {
        String callId = UUID.randomUUID().toString().substring(0, 8);
        long start = System.nanoTime();
        String outcome = "OK";
        String error = null;
        try {
            caller.authorizeTool(tool, requiredScopes);
            return body.get();
        } catch (DeniedException e) {
            outcome = "DENIED";
            error = e.getMessage();
            throw e;
        } catch (RateLimitedException e) {
            outcome = "RATE_LIMITED";
            error = e.getMessage();
            throw e;
        } catch (IllegalArgumentException e) {
            outcome = "REJECTED";
            error = e.getMessage();
            throw e;
        } catch (RuntimeException e) {
            outcome = "ERROR";
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("Tool {} failed (call {})", tool, callId, e);
            throw e;
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            Counter c = counters.computeIfAbsent(tool, k -> new Counter());
            synchronized (c) {
                c.calls++;
                c.totalMs += ms;
                if (outcome.equals("OK")) {
                    c.ok++;
                } else if (!outcome.equals("ERROR")) {
                    c.rejected++;
                } else {
                    c.errors++;
                }
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ts", Instant.now().toString());
            entry.put("callId", callId);
            entry.put("traceId", MDC.get(TraceIdFilter.MDC_KEY));
            entry.put("client", caller.client());
            entry.put("tool", tool);
            entry.put("args", masker.mask(String.valueOf(args)));
            entry.put("durationMs", ms);
            entry.put("outcome", outcome);
            if (error != null) {
                entry.put("error", masker.mask(error));
            }
            audit.info(json.writeValueAsString(entry));
        }
    }

    public Stats stats(String tool) {
        Counter c = counters.get(tool);
        if (c == null) {
            return new Stats(0, 0, 0, 0, 0);
        }
        synchronized (c) {
            return new Stats(c.calls, c.ok, c.rejected, c.errors, c.calls == 0 ? 0 : c.totalMs / c.calls);
        }
    }
}
