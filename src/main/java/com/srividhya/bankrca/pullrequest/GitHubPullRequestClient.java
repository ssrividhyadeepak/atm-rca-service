package com.srividhya.bankrca.pullrequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.srividhya.bankrca.investigation.Investigation.Edit;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real implementation, for GitHub and GitHub Enterprise, over the REST API: makes a
 * branch from the base branch, commits each edited file to it, and opens a DRAFT pull
 * request. Used only when rca.pull-request.mode=github is set on purpose. It never pushes to
 * the base branch and never merges.
 *
 * Each edit is checked again here against the file as it is on the base branch: if the line
 * is no longer what the plan says, nothing is committed for that file and the call fails.
 */
@Component
@ConditionalOnProperty(name = "rca.pull-request.mode", havingValue = "github")
public class GitHubPullRequestClient implements PullRequestClient {

    private final RestClient rest;
    private final URI api;
    private final String repository;
    private final String base;
    private final JsonMapper json = new JsonMapper();

    public GitHubPullRequestClient(PullRequestProps props) {
        if (props.apiUrl() == null || props.apiUrl().isBlank() || props.repository() == null
                || !props.repository().matches("[\\w.-]+/[\\w.-]+")) {
            throw new IllegalStateException("rca.pull-request.mode is github but GIT_API_URL or GIT_PR_REPOSITORY "
                    + "(owner/name) is not set");
        }
        if (props.token() == null || props.token().isBlank()) {
            throw new IllegalStateException("rca.pull-request.mode is github but GIT_PR_TOKEN is not set");
        }
        this.api = URI.create(props.apiUrl().strip().replaceAll("/+$", ""));
        boolean local = "localhost".equals(api.getHost()) || "127.0.0.1".equals(api.getHost());
        if (!"https".equals(api.getScheme()) && !local) {
            throw new IllegalStateException("GIT_API_URL must use https: the token is sent with every call");
        }
        this.repository = props.repository();
        this.base = props.baseBranch() == null || props.baseBranch().isBlank() ? "main" : props.baseBranch();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(props.timeout() == null ? Duration.ofSeconds(30) : props.timeout());
        this.rest = RestClient.builder().baseUrl(api.toString()).requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + props.token().strip())
                .defaultHeader("Accept", "application/vnd.github+json").build();
    }

    @Override
    public String description() {
        return "GitHub " + api + ", repository " + repository + " (real draft pull requests into " + base + ")";
    }

    @Override
    public Created open(Draft draft) {
        if (draft.edits().isEmpty()) {
            throw new IllegalStateException("The fix plan has no edits, so there is nothing to put in a pull request");
        }
        String repo = "/repos/" + repository;
        String owner = repository.substring(0, repository.indexOf('/'));
        try {
            // Already open from an earlier attempt?
            JsonNode open = get(repo + "/pulls?state=open&head=" + owner + ":" + draft.branch());
            if (open.isArray() && open.size() > 0) {
                return created(open.get(0));
            }
            String baseSha = get(repo + "/git/ref/heads/" + base).path("object").path("sha").asString("");
            if (baseSha.isBlank()) {
                throw new IllegalStateException("The repository has no branch '" + base + "'");
            }
            try {
                post(repo + "/git/refs", Map.of("ref", "refs/heads/" + draft.branch(), "sha", baseSha));
            } catch (RestClientResponseException e) {
                // 422: the branch is there from an earlier attempt; anything else is a failure
                if (e.getStatusCode().value() != 422) {
                    throw e;
                }
            }
            Map<String, List<Edit>> byPath = new LinkedHashMap<>();
            draft.edits().forEach(e -> byPath.computeIfAbsent(e.path(), p -> new ArrayList<>()).add(e));
            for (Map.Entry<String, List<Edit>> file : byPath.entrySet()) {
                JsonNode current = get(repo + "/contents/" + file.getKey() + "?ref=" + draft.branch());
                String text = new String(Base64.getMimeDecoder().decode(current.path("content").asString("")),
                        StandardCharsets.UTF_8);
                List<String> lines = new ArrayList<>(text.lines().toList());
                boolean changed = false;
                for (Edit edit : file.getValue()) {
                    if (edit.line() < 1 || edit.line() > lines.size()) {
                        throw new IllegalStateException(file.getKey() + " has no line " + edit.line() + " on " + base);
                    }
                    String now = lines.get(edit.line() - 1);
                    if (now.strip().equals(edit.newCode().strip())) {
                        continue; // applied by an earlier attempt
                    }
                    if (!now.strip().equals(edit.oldCode().strip())) {
                        throw new IllegalStateException("Line " + edit.line() + " of " + file.getKey() + " on " + base
                                + " is no longer what the plan was checked against; record the fix plan again");
                    }
                    // Keep the line's own indentation
                    lines.set(edit.line() - 1, now.substring(0, now.length() - now.stripLeading().length()) + edit.newCode().strip());
                    changed = true;
                }
                if (changed) {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("message", draft.title());
                    body.put("content", Base64.getEncoder().encodeToString((String.join("\n", lines)
                            + (text.endsWith("\n") ? "\n" : "")).getBytes(StandardCharsets.UTF_8)));
                    body.put("sha", current.path("sha").asString());
                    body.put("branch", draft.branch());
                    rest.put().uri(URI.create(api + repo + "/contents/" + file.getKey())).contentType(MediaType.APPLICATION_JSON)
                            .body(json.writeValueAsString(body)).retrieve().body(String.class);
                }
            }
            Map<String, Object> pr = new LinkedHashMap<>();
            pr.put("title", draft.title());
            pr.put("head", draft.branch());
            pr.put("base", base);
            pr.put("body", draft.body());
            pr.put("draft", true);
            return created(post(repo + "/pulls", pr));
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            throw new IllegalStateException(status == 401 ? "The git host rejected the token (401)"
                    : status == 403 ? "The token may not create branches or pull requests in " + repository + " (403)"
                            : status == 404 ? "The git host could not find " + repository + " or a file in it (404)"
                                    : "The git host answered with status " + status);
        } catch (ResourceAccessException e) {
            throw new IllegalStateException("Could not reach the git host at " + api + " ("
                    + e.getMostSpecificCause().getClass().getSimpleName() + ")");
        } catch (JacksonException e) {
            throw new IllegalStateException("The git host did not answer with JSON");
        }
    }

    private Created created(JsonNode pr) {
        String number = pr.path("number").asString("");
        if (number.isBlank()) {
            throw new IllegalStateException("The git host accepted the request but returned no pull request number");
        }
        return new Created(number, pr.path("html_url").asString(null), api.getHost());
    }

    private JsonNode get(String path) {
        return json.readTree(rest.get().uri(URI.create(api + path)).retrieve().body(String.class));
    }

    private JsonNode post(String path, Map<String, Object> body) {
        return json.readTree(rest.post().uri(URI.create(api + path)).contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(body)).retrieve().body(String.class));
    }
}
