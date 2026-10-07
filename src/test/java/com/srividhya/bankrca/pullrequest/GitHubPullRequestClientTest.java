package com.srividhya.bankrca.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.srividhya.bankrca.investigation.Investigation.Edit;
import com.srividhya.bankrca.pullrequest.PullRequestClient.Created;
import com.srividhya.bankrca.pullrequest.PullRequestClient.Draft;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The real client against a stand-in for the GitHub REST API. */
class GitHubPullRequestClientTest {

    private static final String FILE = "svc/src/main/resources/application.yml";
    private static final String CONTENT = "host:\n  auth:\n    timeout-ms: 500\n";

    private HttpServer server;
    private final JsonMapper json = new JsonMapper();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private volatile String authorization;
    private volatile boolean alreadyOpen;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/bank/payments", exchange -> {
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath().substring("/repos/bank/payments".length());
            requests.add(method + " " + path);
            String sent = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!sent.isBlank()) {
                bodies.add(json.readTree(sent));
            }
            int status = 200;
            String answer;
            if (method.equals("GET") && path.equals("/pulls")) {
                answer = alreadyOpen ? "[{\"number\":41,\"html_url\":\"https://git.example/pull/41\"}]" : "[]";
            } else if (path.equals("/git/ref/heads/main")) {
                answer = "{\"object\":{\"sha\":\"base123\"}}";
            } else if (path.equals("/git/refs")) {
                status = 201;
                answer = "{}";
            } else if (method.equals("GET") && path.equals("/contents/" + FILE)) {
                answer = "{\"sha\":\"blob9\",\"content\":\"" + Base64.getEncoder()
                        .encodeToString(CONTENT.getBytes(StandardCharsets.UTF_8)) + "\"}";
            } else if (method.equals("PUT")) {
                answer = "{}";
            } else if (method.equals("POST") && path.equals("/pulls")) {
                status = 201;
                answer = "{\"number\":42,\"html_url\":\"https://git.example/pull/42\"}";
            } else {
                status = 404;
                answer = "{}";
            }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GitHubPullRequestClient client() {
        return new GitHubPullRequestClient(new PullRequestProps("github", null,
                "http://127.0.0.1:" + server.getAddress().getPort(), "bank/payments", "main", "t0ken", Duration.ofSeconds(5)));
    }

    private static Draft draft(String oldCode) {
        return new Draft("INV-1", "rca/inv-1", "[INV-1] Restore the timeout", "body",
                List.of(new Edit(FILE, 3, oldCode, "timeout-ms: 2000")));
    }

    @Test
    void makesABranchCommitsTheEditAndOpensADraft() {
        Created created = client().open(draft("timeout-ms: 500"));

        assertThat(created.number()).isEqualTo("42");
        assertThat(created.url()).isEqualTo("https://git.example/pull/42");
        assertThat(requests).containsExactly("GET /pulls", "GET /git/ref/heads/main", "POST /git/refs",
                "GET /contents/" + FILE, "PUT /contents/" + FILE, "POST /pulls");
        assertThat(authorization).isEqualTo("Bearer t0ken");
        // The branch starts at the base branch; the commit goes to the new branch, never to main
        assertThat(bodies.get(0).get("ref").asString()).isEqualTo("refs/heads/rca/inv-1");
        assertThat(bodies.get(0).get("sha").asString()).isEqualTo("base123");
        JsonNode commit = bodies.get(1);
        assertThat(commit.get("branch").asString()).isEqualTo("rca/inv-1");
        assertThat(commit.get("sha").asString()).isEqualTo("blob9");
        // Only that line changed, with its indentation kept
        assertThat(new String(Base64.getDecoder().decode(commit.get("content").asString()), StandardCharsets.UTF_8))
                .isEqualTo("host:\n  auth:\n    timeout-ms: 2000\n");
        JsonNode pr = bodies.get(2);
        assertThat(pr.get("draft").asBoolean()).isTrue();
        assertThat(pr.get("base").asString()).isEqualTo("main");
        assertThat(pr.get("head").asString()).isEqualTo("rca/inv-1");
    }

    @Test
    void anOpenPullRequestForTheBranchIsReturnedNotDuplicated() {
        alreadyOpen = true;

        assertThat(client().open(draft("timeout-ms: 500")).number()).isEqualTo("41");
        assertThat(requests).containsExactly("GET /pulls");
    }

    @Test
    void nothingIsCommittedWhenTheLineHasMovedOn() {
        assertThatThrownBy(() -> client().open(draft("timeout-ms: 750"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is no longer what the plan was checked against");
        assertThat(requests).noneMatch(r -> r.startsWith("PUT") || r.equals("POST /pulls"));
    }

    @Test
    void needsATokenAndHttps() {
        assertThatThrownBy(() -> new GitHubPullRequestClient(new PullRequestProps("github", null, "https://api.github.com",
                "bank/payments", "main", "", null))).hasMessageContaining("GIT_PR_TOKEN is not set");
        assertThatThrownBy(() -> new GitHubPullRequestClient(new PullRequestProps("github", null, "http://git.example",
                "bank/payments", "main", "t", null))).hasMessageContaining("must use https");
    }
}
