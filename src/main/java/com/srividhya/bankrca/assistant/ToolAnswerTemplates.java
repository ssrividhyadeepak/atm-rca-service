package com.srividhya.bankrca.assistant;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An answer built from tool results by fixed templates, with no model involved. It is what
 * the scripted model says, and what the assistant falls back to when a real model's answer
 * fails the grounding check: plainer than a model's answer, and always true to the tools.
 */
final class ToolAnswerTemplates {

    private static final JsonMapper JSON = new JsonMapper();

    private ToolAnswerTemplates() {
    }

    static String render(List<ToolResponse> results) {
        List<String> parts = new ArrayList<>();
        for (ToolResponse r : results) {
            JsonNode data;
            try {
                data = JSON.readTree(r.responseData());
            } catch (JacksonException e) {
                data = null;
            }
            if (data == null || !data.isObject()) {
                // Not a result: the tool's refusal or failure, passed back as text
                parts.add(r.name() + " could not answer: " + r.responseData());
            } else if (r.name().equals("getFailureSummary")) {
                StringBuilder sb = new StringBuilder(data.get("headline").asString());
                for (JsonNode f : data.get("findings")) {
                    if (f.get("rank").asInt() <= 3) {
                        sb.append(' ').append(f.get("rank").asInt()).append(". ").append(f.get("severity").asString())
                                .append(' ').append(f.get("problem").asString()).append(" in ")
                                .append(f.get("component").asString()).append(" (").append(f.get("count").asInt())
                                .append(" events, ").append(f.get("timePattern").asString()).append(").");
                    }
                }
                parts.add(sb.toString());
            } else if (r.name().equals("getFinding")) {
                parts.add("Finding " + data.get("rank").asInt() + ", " + data.get("title").asString() + " ("
                        + data.get("severity").asString() + ", confidence " + data.get("confidence").asString()
                        + "). Likely cause: " + data.get("likelyCause").asString() + " Next step: "
                        + data.get("suggestedAction").asString());
            } else if (r.name().equals("draftIncident")) {
                JsonNode d = data.get("draft");
                parts.add((data.get("created").asBoolean() ? "Incident draft " + d.get("id").asString() + " created: "
                        : "An incident for this problem already exists, " + d.get("id").asString() + " ("
                                + d.get("status").asString() + "): ")
                        + d.get("shortDescription").asString() + " Priority " + d.get("priority").asString()
                        + ", assigned to " + d.get("assignmentGroup").asString() + "."
                        + (d.get("status").asString().equals("DRAFT")
                                ? " Nothing has been sent: it is waiting for a person to approve it." : ""));
            } else {
                boolean runbook = r.name().equals("lookupRunbook");
                if (data.get("verdict").asString().equals("NO_MATCH")) {
                    parts.add(runbook ? "There is no runbook for this." : "Nothing similar is on record.");
                } else {
                    for (JsonNode h : data.get("hits")) {
                        if (h.get("related").asBoolean()) {
                            parts.add((runbook ? "Runbook " : "Past RCA ") + h.get("id").asString() + ", \""
                                    + h.get("title").asString() + "\": " + h.get("summary").asString());
                        }
                    }
                }
            }
        }
        return String.join(" ", parts);
    }
}
