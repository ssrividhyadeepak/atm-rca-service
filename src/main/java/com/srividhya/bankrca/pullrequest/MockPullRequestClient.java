package com.srividhya.bankrca.pullrequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for the git host: gives the pull request a number and appends what would have
 * been opened to mock-pull-requests.jsonl. This is the default, also with the prod profile.
 */
@Component
@ConditionalOnProperty(name = "rca.pull-request.mode", havingValue = "mock", matchIfMissing = true)
public class MockPullRequestClient implements PullRequestClient {

    private final Path file;
    private final AtomicInteger next = new AtomicInteger(9001);
    private final Map<String, Created> byBranch = new ConcurrentHashMap<>();
    private final JsonMapper json = new JsonMapper();

    public MockPullRequestClient(PullRequestProps props) {
        this.file = props.mockFile() == null ? Path.of("data/pull-requests/mock-pull-requests.jsonl") : props.mockFile();
    }

    @Override
    public String description() {
        return "mock: nothing is opened; recorded in " + file;
    }

    @Override
    public synchronized Created open(Draft draft) {
        Created existing = byBranch.get(draft.branch());
        if (existing != null) {
            return existing;
        }
        String number = String.valueOf(next.getAndIncrement());
        Created created = new Created(number, "mock://pull-requests/" + number, "mock");
        Map<String, Object> sent = new LinkedHashMap<>();
        sent.put("number", number);
        sent.put("investigation", draft.investigationId());
        sent.put("draft", true);
        sent.put("head", draft.branch());
        sent.put("title", draft.title());
        sent.put("edits", draft.edits());
        sent.put("body", draft.body());
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, json.writeValueAsString(sent) + "\n", StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byBranch.put(draft.branch(), created);
        return created;
    }
}
