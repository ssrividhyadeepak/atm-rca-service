package com.srividhya.bankrca.trace;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.srividhya.bankrca.config.RcaProperties;
import com.srividhya.bankrca.config.RcaProperties.MonitoredComponent;
import com.srividhya.bankrca.failure.RawEventParser;
import com.srividhya.bankrca.failure.RawEventParser.Parsed;
import com.srividhya.bankrca.security.PayloadMasker;
import com.srividhya.bankrca.security.PiiMasker;
import com.srividhya.bankrca.splunk.SplunkClient;
import com.srividhya.bankrca.trace.TraceTimeline.Call;
import com.srividhya.bankrca.trace.TraceTimeline.Divergence;
import com.srividhya.bankrca.trace.TraceTimeline.Step;

import tools.jackson.databind.JsonNode;

/**
 * Follows one request by its trace id: fetches every event that carries the id, whichever
 * component logged it, orders them, pairs each call to a dependency with its answer, keeps
 * the logged bodies (masked), and works out where the request left the normal path. All of
 * it is done in code, so the same trace always reads the same way.
 *
 * The lines are recognised by how they start, after the tracing prefix:
 * "IN ...", "OUT target operation ...", "OUT_RESP target operation status=...", "RESP status=...",
 * each optionally followed by " payload={json}". These must match what the applications log.
 */
@Service
public class TraceService {

    public static class TraceNotFoundException extends RuntimeException {
        public TraceNotFoundException(String message) {
            super(message);
        }
    }

    private static final int MAX_EVENTS = 300;
    private static final Pattern TRACE_ID = Pattern
            .compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern LINE = Pattern.compile("^(IN|OUT_RESP|OUT|RESP)\\s+(.*)$", Pattern.DOTALL);
    private static final Pattern KEY_VALUE = Pattern.compile("\\b(status|latencyMs|error)=(\\S+)");
    private static final Pattern NULL_FIELD = Pattern.compile("because \"(\\w+)\" is null");
    private static final Pattern MISSING_FIELD = Pattern.compile("(?i)(?:mandatory|required) field missing[^,]*,\\s*(\\w+)");

    private final SplunkClient splunk;
    private final RawEventParser parser;
    private final PiiMasker masker;
    private final PayloadMasker payloads;
    private final String namespace;
    private final List<MonitoredComponent> components;

    public TraceService(SplunkClient splunk, RawEventParser parser, PiiMasker masker, PayloadMasker payloads,
            RcaProperties props) {
        this.splunk = splunk;
        this.parser = parser;
        this.masker = masker;
        this.payloads = payloads;
        this.namespace = props.monitor().namespace();
        this.components = props.monitor().components();
    }

    /**
     * @throws IllegalArgumentException when the id is not a trace id
     * @throws TraceNotFoundException when no event carries it in the window
     */
    public TraceTimeline trace(String traceId, Instant from, Instant to) {
        String id = traceId == null ? "" : traceId.strip();
        if (!TRACE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("'traceId' must look like 80cc944e-7925-7d33-3799-2694c2a6898a: "
                    + "take one from splunkFindFailures or a finding's sampleTraceIds");
        }
        List<Map<String, Object>> rows = splunk.search(SplunkClient.TRACE_EVENTS, from, to,
                Map.of("namespace", namespace, "traceId", id, "limit", MAX_EVENTS + 1));
        if (rows.isEmpty()) {
            throw new TraceNotFoundException("No events carry trace id " + id + " between " + from + " and " + to);
        }
        boolean truncated = rows.size() > MAX_EVENTS;
        if (truncated) {
            rows = rows.subList(0, MAX_EVENTS);
        }

        List<Step> steps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String bankId = null;
        Instant start = null;
        Instant end = null;
        for (Map<String, Object> row : rows) {
            Parsed p = parser.parse(String.valueOf(row.get("_raw")), row.get("ts") == null ? null : row.get("ts").toString());
            Instant at = time(p.timestamp());
            if (start == null) {
                start = at;
            }
            end = at == null ? end : at;
            if (bankId == null) {
                bankId = p.bankId();
            }
            if (p.component() != null) {
                seen.add(p.component());
            }
            steps.add(step(steps.size(), p, start, at));
        }

        List<Call> calls = calls(steps);
        Divergence divergence = divergence(steps, calls);
        boolean failed = divergence != null || steps.stream().anyMatch(s -> "RESP".equals(s.kind())
                && s.status() != null && s.status() >= 400);
        // The request's own type is that of the first monitored component it reached
        String transaction = seen.stream().map(this::transaction).filter(t -> t != null).findFirst().orElse(null);
        return new TraceTimeline(id, bankId, transaction, failed ? "FAILED" : "COMPLETED",
                start == null ? null : start.toString(), end == null ? null : end.toString(),
                start == null || end == null ? 0 : Duration.between(start, end).toMillis(), List.copyOf(seen), steps,
                calls, divergence, truncated, splunk.description());
    }

    private Step step(int index, Parsed p, Instant start, Instant at) {
        String text = p.message() == null ? "" : p.message();
        String kind = "LOG";
        String target = null;
        String operation = null;
        JsonNode payload = null;
        if (p.exception() != null || p.stackTrace() != null) {
            kind = "EXCEPTION";
        } else {
            Matcher line = LINE.matcher(text);
            if (line.matches()) {
                kind = line.group(1);
                String rest = line.group(2);
                int at2 = rest.indexOf(" payload=");
                if (at2 >= 0) {
                    payload = payloads.mask(rest.substring(at2 + " payload=".length()));
                    rest = rest.substring(0, at2);
                } else if (rest.startsWith("payload=")) {
                    payload = payloads.mask(rest.substring("payload=".length()));
                    rest = "";
                }
                if (kind.startsWith("OUT")) {
                    String[] words = rest.strip().split("\\s+");
                    target = words.length > 0 && !words[0].contains("=") ? words[0] : null;
                    operation = words.length > 1 && !words[1].contains("=") ? words[1] : null;
                }
                text = (kind + " " + rest).strip();
            }
        }
        Map<String, String> fields = new HashMap<>();
        Matcher kv = KEY_VALUE.matcher(text);
        while (kv.find()) {
            fields.putIfAbsent(kv.group(1), kv.group(2));
        }
        return new Step(index, p.timestamp(), start == null || at == null ? 0 : Duration.between(start, at).toMillis(),
                p.component(), p.pod(), p.level(), kind, target, operation, number(fields.get("status")),
                fields.get("latencyMs") == null ? null : longOrNull(fields.get("latencyMs")), fields.get("error"),
                masker.mask(text), p.exception(), payload);
    }

    /** Each OUT with the next OUT_RESP from the same component to the same target and operation. */
    private static List<Call> calls(List<Step> steps) {
        List<Call> calls = new ArrayList<>();
        Set<Integer> used = new LinkedHashSet<>();
        for (Step out : steps) {
            if (!"OUT".equals(out.kind())) {
                continue;
            }
            Step answer = null;
            for (Step s : steps) {
                if (s.index() > out.index() && "OUT_RESP".equals(s.kind()) && !used.contains(s.index())
                        && same(s.component(), out.component()) && same(s.target(), out.target())
                        && same(s.operation(), out.operation())) {
                    answer = s;
                    break;
                }
            }
            if (answer == null) {
                calls.add(new Call(out.component(), out.target(), out.operation(), "NO_RESPONSE", null, null, null,
                        out.index(), null));
            } else {
                used.add(answer.index());
                boolean failed = answer.error() != null || (answer.status() != null && answer.status() >= 400);
                calls.add(new Call(out.component(), out.target(), out.operation(), failed ? "FAILED" : "OK",
                        answer.status(), answer.latencyMs(), answer.error(), out.index(), answer.index()));
            }
        }
        return calls;
    }

    /**
     * The first thing that went wrong, by fixed rules: a failed call to a dependency; a
     * dependency that said no; an exception naming a null or missing field; any other exception.
     */
    private static Divergence divergence(List<Step> steps, List<Call> calls) {
        for (Call call : calls) {
            if (call.outcome().equals("FAILED")) {
                boolean timeout = "timeout".equalsIgnoreCase(call.error()) || Integer.valueOf(504).equals(call.status());
                return new Divergence(timeout ? "TIMEOUT" : "DOWNSTREAM_ERROR", call.from() + " -> " + call.target(),
                        call.from() + " called " + call.target() + " " + call.operation() + " and got "
                                + (call.status() == null ? "no status" : "status " + call.status())
                                + (call.latencyMs() == null ? "" : " after " + call.latencyMs() + "ms")
                                + (call.error() == null ? "" : " (" + call.error() + ")"),
                        call.responseStep());
            }
            if (call.outcome().equals("NO_RESPONSE") && call.requestStep() != null) {
                // Only a fault if nothing else explains the failure: checked after the exceptions below
                continue;
            }
            Step answer = call.responseStep() == null ? null : steps.get(call.responseStep());
            if (answer != null && answer.payload() != null) {
                String decision = answer.payload().path("decision").asString("");
                if (decision.equalsIgnoreCase("DECLINE") || decision.equalsIgnoreCase("DECLINED")
                        || decision.equalsIgnoreCase("DENY") || decision.equalsIgnoreCase("BLOCK")) {
                    return new Divergence("DECLINED", call.from() + " -> " + call.target(), call.target() + " answered "
                            + call.operation() + " with decision " + decision, answer.index());
                }
            }
        }
        for (Step s : steps) {
            if (!"EXCEPTION".equals(s.kind())) {
                continue;
            }
            Matcher nullField = NULL_FIELD.matcher(s.message());
            if (nullField.find()) {
                String field = nullField.group(1);
                // Was it already null in the request this component received?
                Step received = null;
                for (Step in : steps) {
                    if (in.index() < s.index() && "IN".equals(in.kind()) && same(in.component(), s.component())
                            && in.payload() != null && in.payload().has(field) && in.payload().get(field).isNull()) {
                        received = in;
                    }
                }
                return new Divergence("NULL_FIELD", s.component(), "\"" + field + "\" was null in " + s.component()
                        + (received == null ? "" : "; the request it received (step " + received.index()
                                + ") already carried \"" + field + "\": null"), s.index());
            }
            Matcher missing = MISSING_FIELD.matcher(s.message());
            if (missing.find()) {
                return new Divergence("MISSING_FIELD", s.component(), "A required field, \"" + missing.group(1)
                        + "\", was missing from a response " + s.component() + " received", s.index());
            }
            return new Divergence("EXCEPTION", s.component(), s.component() + " threw "
                    + (s.exception() == null ? "an exception" : s.exception().substring(s.exception().lastIndexOf('.') + 1))
                    + " with no failed call to a dependency before it", s.index());
        }
        for (Call call : calls) {
            if (call.outcome().equals("NO_RESPONSE")) {
                return new Divergence("TIMEOUT", call.from() + " -> " + call.target(), call.from() + " called "
                        + call.target() + " " + call.operation() + " and no answer was logged", call.requestStep());
            }
        }
        return null;
    }

    private String transaction(String component) {
        for (MonitoredComponent c : components) {
            StringBuilder regex = new StringBuilder();
            for (String part : c.pattern().split("\\*", -1)) {
                if (regex.length() > 0) {
                    regex.append(".*");
                }
                regex.append(Pattern.quote(part));
            }
            // Gateways and the UI see every request; the type comes from the service that handles it
            if (component.matches(regex.toString()) && !List.of("gateway", "ui").contains(c.transaction())) {
                return c.transaction();
            }
        }
        return null;
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static Integer number(String value) {
        Long l = longOrNull(value);
        return l == null ? null : l.intValue();
    }

    private static Long longOrNull(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant time(String timestamp) {
        try {
            return timestamp == null ? null : Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
