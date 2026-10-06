package com.srividhya.bankrca.incident;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for the ticket system: gives the incident a number and appends what would have
 * been sent to mock-incidents.jsonl, so the whole workflow can be run without creating a
 * real ticket. This is the default, also with the prod profile.
 */
@Component
@ConditionalOnProperty(name = "rca.incident.mode", havingValue = "mock", matchIfMissing = true)
public class MockIncidentClient implements IncidentClient {

    private final Path file;
    private final AtomicInteger next = new AtomicInteger(9000001);
    private final Map<String, String> numbers = new ConcurrentHashMap<>();
    private final JsonMapper json = new JsonMapper();

    public MockIncidentClient(@Value("${rca.incident.mock-file}") Path file) {
        this.file = file;
    }

    @Override
    public String description() {
        return "mock: nothing is sent; recorded in " + file;
    }

    @Override
    public synchronized Created create(IncidentDraft draft) {
        String existing = numbers.get(draft.id());
        if (existing != null) {
            return new Created(existing, "mock");
        }
        String number = "INC" + next.getAndIncrement();
        Map<String, Object> sent = new LinkedHashMap<>();
        sent.put("number", number);
        sent.put("correlation_id", draft.id());
        sent.put("short_description", draft.shortDescription());
        sent.put("priority", draft.priority());
        sent.put("cmdb_ci", draft.component());
        sent.put("assignment_group", draft.assignmentGroup());
        sent.put("category", draft.category());
        sent.put("description", draft.description());
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, json.writeValueAsString(sent) + "\n", StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        numbers.put(draft.id(), number);
        return new Created(number, "mock");
    }
}
