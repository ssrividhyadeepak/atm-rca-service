package com.srividhya.bankrca.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The service with rca.security.mode=dev, called over real HTTP with tokens signed by the dev
 * key: authentication, a scope per endpoint and per tool, rate limits, trace ids and the audit trail.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "rca.security.mode=dev",
        "rca.security.dev-keys-dir=build/test-data/dev-keys",
        "rca.rate-limit.assistant-per-minute=3",
        "rca.rate-limit.tool-per-minute=5" })
@ActiveProfiles("test")
class SecurityTest {

    @LocalServerPort
    int port;

    @Autowired
    SecurityProps props;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = new JsonMapper();

    private KeyPair keys() {
        return DevKeys.loadOrCreate(props.devKeysDir());
    }

    private String token(String client, String scopes) {
        return DevTokens.mint(keys(), DevKeys.ISSUER, client, scopes, DevTokens.AUDIENCE, Duration.ofMinutes(5));
    }

    private HttpResponse<String> send(String method, String path, String token, String body, String traceparent)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (traceparent != null) {
            b.header("traceparent", traceparent);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int status(String method, String path, String token) throws Exception {
        return send(method, path, token, null, null).statusCode();
    }

    @Test
    void refusesARequestWithoutAValidToken() throws Exception {
        HttpResponse<String> none = send("GET", "/api/rca/latest", null, null, null);
        assertThat(none.statusCode()).isEqualTo(401);
        // Points the client at the metadata that says where to get a token
        assertThat(none.headers().firstValue("WWW-Authenticate").orElseThrow()).startsWith("Bearer")
                .contains("/.well-known/oauth-protected-resource");

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair someoneElse = generator.generateKeyPair();
        assertThat(status("GET", "/api/rca/latest", "not-a-jwt")).isEqualTo(401);
        assertThat(status("GET", "/api/rca/latest", DevTokens.mint(someoneElse, DevKeys.ISSUER, "mallory", Scopes.READ_ONLY,
                DevTokens.AUDIENCE, Duration.ofHours(1)))).as("signed with another key").isEqualTo(401);
        assertThat(status("GET", "/api/rca/latest", DevTokens.mint(keys(), DevKeys.ISSUER, "old", Scopes.READ_ONLY,
                DevTokens.AUDIENCE, Duration.ofHours(-1)))).as("expired").isEqualTo(401);
        assertThat(status("GET", "/api/rca/latest", DevTokens.mint(keys(), DevKeys.ISSUER, "other", Scopes.READ_ONLY,
                "some-other-api", Duration.ofHours(1)))).as("issued for another audience").isEqualTo(401);
        assertThat(status("GET", "/api/rca/latest", DevTokens.mint(keys(), "http://localhost/another-issuer", "other",
                Scopes.READ_ONLY, DevTokens.AUDIENCE, Duration.ofHours(1)))).as("another issuer").isEqualTo(401);
    }

    @Test
    void healthAndTheMetadataNeedNoToken() throws Exception {
        assertThat(status("GET", "/actuator/health", null)).isEqualTo(200);

        JsonNode metadata = json.readTree(send("GET", "/.well-known/oauth-protected-resource", null, null, null).body());
        assertThat(metadata.get("authorization_servers").get(0).asString()).isEqualTo(DevKeys.ISSUER);
        assertThat(metadata.get("scopes_supported").toString()).contains("rca:read", "rca:write", "logs:read", "kb:read",
                "code:read");
    }

    @Test
    void eachEndpointNeedsItsScope() throws Exception {
        String write = token("operator", "rca:write");
        String read = token("viewer", "rca:read");
        String logs = token("log-reader", "logs:read");
        String kb = token("librarian", "kb:read");
        String code = token("developer", "code:read");
        assertThat(status("POST", "/api/rca", write)).isEqualTo(200);

        // The right scope opens the endpoint...
        assertThat(status("GET", "/api/rca/latest", read)).isEqualTo(200);
        assertThat(status("GET", "/api/rca/latest.md", read)).isEqualTo(200);
        assertThat(status("GET", "/api/rca/input", read)).isEqualTo(200);
        assertThat(status("GET", "/api/runs", read)).isEqualTo(200);
        assertThat(status("GET", "/api/correlation", read)).isEqualTo(200);
        assertThat(status("GET", "/api/failures", logs)).isEqualTo(200);
        assertThat(status("GET", "/api/knowledge/search?q=LedgerPostingException", kb)).isEqualTo(200);
        assertThat(send("POST", "/api/source/locate", code, null, null).statusCode()).as("authorized; the empty body is refused")
                .isIn(400, 415);
        // ...and another scope does not
        assertThat(status("GET", "/api/rca/latest", kb)).isEqualTo(403);
        assertThat(status("GET", "/api/failures", read)).as("raw events need logs:read").isEqualTo(403);
        assertThat(status("POST", "/api/rca", read)).as("starting an analysis needs rca:write").isEqualTo(403);
        assertThat(status("POST", "/api/runs", read)).isEqualTo(403);
        assertThat(status("POST", "/api/knowledge/reload", kb)).isEqualTo(403);
        assertThat(status("GET", "/api/knowledge/search?q=x", read)).isEqualTo(403);
        assertThat(status("POST", "/api/source/locate", read)).isEqualTo(403);
        // Anything not listed is closed, whatever the token
        assertThat(status("GET", "/actuator/env", token("admin", String.join(" ", Scopes.ALL)))).isEqualTo(403);
        assertThat(status("GET", "/api/anything-else", token("admin", String.join(" ", Scopes.ALL)))).isEqualTo(403);
    }

    @Test
    void aTokenCanOnlyCallTheToolsItsScopesAllow() throws Exception {
        assertThat(status("POST", "/api/rca", token("operator", "rca:write"))).isEqualTo(200);
        String kbOnly = token("kb-only", "kb:read");

        // Any valid token may list the tools
        assertThat(json.readTree(send("GET", "/api/tools", kbOnly, null, null).body())).hasSize(4);
        assertThat(send("POST", "/api/tools/lookupRunbook", kbOnly, "{\"query\":\"LedgerPostingException\"}", null)
                .statusCode()).isEqualTo(200);

        HttpResponse<String> denied = send("POST", "/api/tools/getFailureSummary", kbOnly, "{}", null);
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).contains("Access denied: getFailureSummary needs scope rca:read");
        assertThat(lastAuditLine("kb-only", "getFailureSummary")).contains("\"outcome\":\"DENIED\"");

        // The assistant acts with the caller's scopes: the tool it wants is refused, and it says so
        JsonNode answer = json.readTree(send("POST", "/api/assistant/ask", kbOnly, "{\"question\":\"What failed today?\"}",
                null).body());
        assertThat(answer.get("answer").asString()).contains("getFailureSummary could not answer")
                .contains("needs scope rca:read");
        assertThat(answer.toString()).doesNotContain("HostAuthTimeoutException");
    }

    @Test
    void carriesTheCallersTraceIdAndIdentityIntoTheAuditLog() throws Exception {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

        HttpResponse<String> r = send("POST", "/api/tools/lookupRunbook", token("tracer", "kb:read"),
                "{\"query\":\"NullPointerException\"}", "00-" + traceId + "-00f067aa0ba902b7-01");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("X-Trace-Id")).contains(traceId);
        assertThat(lastAuditLine("tracer", "lookupRunbook")).contains("\"traceId\":\"" + traceId + "\"")
                .contains("\"client\":\"tracer\"").contains("\"outcome\":\"OK\"");
        // No header, or a malformed one: a trace id is made up, also for refused requests
        assertThat(send("GET", "/api/runs", null, null, "drop table").headers().firstValue("X-Trace-Id").orElseThrow())
                .matches("[0-9a-f]{32}");
    }

    @Test
    void limitsCallsPerClient() throws Exception {
        String greedy = token("greedy", "kb:read rca:read");
        String question = "{\"question\":\"Is there a runbook for LedgerPostingException?\"}";

        for (int i = 0; i < 3; i++) {
            assertThat(send("POST", "/api/assistant/ask", greedy, question, null).statusCode()).as("call " + i).isEqualTo(200);
        }
        HttpResponse<String> fourth = send("POST", "/api/assistant/ask", greedy, question, null);
        assertThat(fourth.statusCode()).isEqualTo(429);
        assertThat(fourth.headers().firstValue("Retry-After").orElseThrow()).matches("\\d+");
        assertThat(fourth.body()).contains("at most 3 of these calls per minute");
        // Another client, and another kind of call, still have their own allowance
        assertThat(send("POST", "/api/assistant/ask", token("patient", "kb:read"), question, null).statusCode()).isEqualTo(200);
        assertThat(status("GET", "/api/knowledge", greedy)).isEqualTo(200);
    }

    @Test
    void limitsCallsOfOneToolPerClient() throws Exception {
        String looper = token("looper", "kb:read");
        String call = "{\"query\":\"CoreBankingTimeoutException\"}";

        for (int i = 0; i < 5; i++) {
            assertThat(send("POST", "/api/tools/searchHistoricalRca", looper, call, null).statusCode()).isEqualTo(200);
        }
        HttpResponse<String> sixth = send("POST", "/api/tools/searchHistoricalRca", looper, call, null);
        assertThat(sixth.statusCode()).isEqualTo(429);
        assertThat(sixth.body()).contains("Rate limit reached for searchHistoricalRca: at most 5 calls per minute");
        assertThat(lastAuditLine("looper", "searchHistoricalRca")).contains("\"outcome\":\"RATE_LIMITED\"");
        assertThat(send("POST", "/api/tools/lookupRunbook", looper, call, null).statusCode()).as("another tool").isEqualTo(200);
    }

    private String lastAuditLine(String client, String tool) throws Exception {
        List<String> lines = Files.readAllLines(Path.of("build/test-data/logs/audit.log"));
        return lines.stream().filter(l -> l.contains("\"client\":\"" + client + "\"") && l.contains("\"tool\":\"" + tool + "\""))
                .reduce((a, b) -> b).orElseThrow();
    }
}
