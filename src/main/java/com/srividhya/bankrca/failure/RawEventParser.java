package com.srividhya.bankrca.failure;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Takes one raw Splunk event apart. The events are JSON written by the container platform:
 *
 * <pre>
 * {"@timestamp":"2026-10-05T01:03:46.716672092Z","hostname":"...",
 *  "kubernetes":{"container_name":"...","namespace_name":"...","pod_name":"..."},
 *  "level":"info","message":"&lt;application log line&gt;",
 *  "openshift":{"labels":{"clustername":"...","datacenter":"..."}}}
 * </pre>
 *
 * and the application log line inside "message" is
 *
 * <pre>
 * 2026-10-04T18:03:46,716-07:00 -- LEVEL: INFO com.example.Class 928202595 -[http-nio-8080-exec-6] --Q1231-80cc944e-7925-7d33-3799-2694c2a6898a- text...
 * </pre>
 *
 * where {@code --Q1231-80cc944e-...-} is the tracing metadata: bank id, then trace id. UI events
 * carry it as {@code BANK ID:Q0457} (the label is configurable) and {@code CustomerTrackingSessionId:...} in the text instead.
 * Nothing here throws on an unexpected event: whatever can be read is kept.
 */
@Component
public class RawEventParser {

    /** What the event itself says; the caller adds the transaction label and masking. */
    public record Parsed(String timestamp, String component, String pod, String namespace, String cluster,
            String host, String level, String logger, String thread, String bankId, String traceId, String sessionId,
            String exception, String message, String stackTrace, boolean structured) {
    }

    private static final Pattern LOG_LINE = Pattern.compile(
            "^\\S+\\s+--\\s+LEVEL:\\s*(?<level>\\w+)\\s+(?<logger>[\\w.$]+)\\s+\\d+\\s+-\\[(?<thread>[^\\]]*)\\]\\s*(?<text>.*)$",
            Pattern.DOTALL);
    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern TRACE_PREFIX = Pattern.compile("^--(?<bank>[0-9A-Za-z]{2,10})-(?<trace>" + UUID + ")-\\s*");
    private static final Pattern SESSION_ID = Pattern.compile("\\b\\w*Tracking-?Session-?I[dD]\"?\\s*[:=]\\s*\\\\?\"?([0-9A-Za-z-]{8,64})");
    // A fully qualified class ending in Exception or Error
    private static final Pattern EXCEPTION = Pattern.compile("\\b((?:[a-zA-Z_][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error))\\b");
    // Stack frames follow a line break; some log shippers turn that into U+2028
    private static final Pattern FIRST_FRAME = Pattern.compile("[\\n\\r\\u2028]+\\s*at\\s+[\\w.$]+\\(");

    private final JsonMapper json = new JsonMapper();
    private final Pattern labelledId;

    /** @param idLabel the label in front of the id on UI events, e.g. "BANK ID" */
    @Autowired
    public RawEventParser(@Value("${rca.parser.id-label:BANK ID}") String idLabel) {
        this.labelledId = Pattern.compile("\\b" + Pattern.quote(idLabel.strip()) + ":\\s*([0-9A-Za-z]{2,10})\\b");
    }

    public RawEventParser() {
        this("BANK ID");
    }

    /**
     * @param raw the raw event text
     * @param fallbackTimestamp Splunk's own event time, used when the event has no "@timestamp"
     */
    public Parsed parse(String raw, String fallbackTimestamp) {
        JsonNode event = null;
        if (raw != null) {
            try {
                event = json.readTree(raw);
            } catch (JacksonException e) {
                // not JSON: handled below
            }
        }
        if (event == null || !event.isObject()) {
            // Not the platform's JSON: treat the whole text as the application log line
            return line(raw == null ? "" : raw, fallbackTimestamp, null, null, null, null, null, null, false);
        }
        JsonNode kubernetes = event.path("kubernetes");
        JsonNode labels = event.path("openshift").path("labels");
        String timestamp = text(event.path("@timestamp"));
        return line(event.path("message").asString(""), timestamp != null ? timestamp : fallbackTimestamp,
                text(kubernetes.path("container_name")), text(kubernetes.path("pod_name")),
                text(kubernetes.path("namespace_name")), text(labels.path("clustername")),
                text(event.path("hostname")), text(event.path("level")), true);
    }

    private Parsed line(String message, String timestamp, String component, String pod, String namespace,
            String cluster, String host, String platformLevel, boolean structured) {
        String level = platformLevel == null ? null : platformLevel.toUpperCase();
        String logger = null;
        String thread = null;
        String text = message.strip();
        Matcher line = LOG_LINE.matcher(text);
        if (line.matches()) {
            level = line.group("level").toUpperCase();
            logger = line.group("logger");
            thread = line.group("thread");
            text = line.group("text");
        }

        String bankId = null;
        String traceId = null;
        Matcher trace = TRACE_PREFIX.matcher(text);
        if (trace.find()) {
            bankId = trace.group("bank");
            traceId = trace.group("trace");
            text = text.substring(trace.end());
        } else {
            Matcher bank = labelledId.matcher(text);
            if (bank.find()) {
                bankId = bank.group(1);
            }
        }
        Matcher session = SESSION_ID.matcher(text);
        String sessionId = session.find() ? session.group(1) : null;

        String stackTrace = null;
        Matcher frame = FIRST_FRAME.matcher(text);
        if (frame.find()) {
            stackTrace = text.substring(frame.start()).replace(' ', '\n').replaceAll("[\\n\\r]+\\s*at ", "\n\tat ")
                    .strip();
            text = text.substring(0, frame.start());
        }
        Matcher exception = EXCEPTION.matcher(text);
        String exceptionClass = exception.find() ? exception.group(1) : null;
        if (stackTrace != null) {
            // Keep the exception line on top of its frames, the way a stack trace reads
            stackTrace = text.strip() + "\n\t" + stackTrace;
        }
        return new Parsed(timestamp, component, pod, namespace, cluster, host, level, logger, thread, bankId, traceId,
                sessionId, exceptionClass, blankToNull(text.strip()), stackTrace, structured && line.matches());
    }

    private static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : blankToNull(node.asString());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
