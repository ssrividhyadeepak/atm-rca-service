package com.srividhya.bankrca.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Masks a logged request or response body before it leaves the service. A payload is
 * structured, so it is masked by field name first - a card number is masked because the
 * field is called "pan", whatever its value looks like - and every remaining string then
 * goes through the pattern masker as well. Field names and structure are kept: they are what
 * shows where a transaction went wrong.
 */
@Component
public class PayloadMasker {

    /** Field names whose values are never passed on. Matched anywhere in the name, ignoring case. */
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(?i)pan|card|account|acct|iban|ssn|taxid|passport|license|email|phone|mobile|address|street|zip|postal"
                    + "|dob|birth|firstname|lastname|fullname|customername|cvv|cvc|pin|password|secret|token|track");
    private static final String MASKED = "***";

    private final PiiMasker patterns;
    private final JsonMapper json = new JsonMapper();

    public PayloadMasker(PiiMasker patterns) {
        this.patterns = patterns;
    }

    /**
     * @return the payload as JSON with sensitive values replaced, or, when the text is not
     *         JSON, the text after pattern masking
     */
    public JsonNode mask(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode node = json.readTree(payload);
            if (node.isObject() || node.isArray()) {
                return maskNode(node);
            }
        } catch (JacksonException e) {
            // not JSON: fall through
        }
        return json.getNodeFactory().stringNode(patterns.mask(payload));
    }

    private JsonNode maskNode(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                names.add(field.getKey());
            }
            for (String name : names) {
                JsonNode value = object.get(name);
                if (SENSITIVE_FIELD.matcher(name).find() && !value.isNull() && !value.isContainer()) {
                    // null stays null: a missing value is evidence, not data
                    object.put(name, MASKED);
                } else {
                    object.set(name, maskNode(value));
                }
            }
            return object;
        }
        if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                array.set(i, maskNode(array.get(i)));
            }
            return array;
        }
        return node.isString() ? json.getNodeFactory().stringNode(patterns.mask(node.asString())) : node;
    }
}
