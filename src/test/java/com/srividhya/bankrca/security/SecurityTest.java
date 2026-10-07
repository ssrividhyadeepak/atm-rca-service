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
                "code:read", "change:read", "investigation:write", "investigation:approve", "pr:write", "incident:read", "incident:write", "incident:approve");
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
        assertThat(json.readTree(send("GET", "/api/tools", kbOnly, null, null).body())).hasSize(16);
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

    @Test
    void anAssistantMayDraftAnIncidentButOnlyAPersonWithTheScopeMayApproveIt() throws Exception {
        assertThat(status("POST", "/api/rca", token("operator", "rca:write"))).isEqualTo(200);
        String copilot = token("copilot", "rca:read incident:write incident:read");
        String oncall = token("oncall.priya", "incident:approve incident:read");

        // Drafting needs incident:write, through the endpoint and through the tool
        assertThat(send("POST", "/api/incidents/drafts", token("viewer", "rca:read incident:read"), "{\"rank\":2}", null)
                .statusCode()).isEqualTo(403);
        HttpResponse<String> toolDenied = send("POST", "/api/tools/draftIncident", token("viewer2", "rca:read"),
                "{\"rank\":2}", null);
        assertThat(toolDenied.statusCode()).isEqualTo(403);
        assertThat(toolDenied.body()).contains("draftIncident needs scope incident:write");

        JsonNode drafted = json.readTree(send("POST", "/api/tools/draftIncident", copilot, "{\"rank\":2}", null).body());
        String id = drafted.get("draft").get("id").asString();
        assertThat(drafted.get("draft").get("createdBy").asString()).isEqualTo("copilot");

        // The assistant's token cannot approve: it lacks the scope
        assertThat(send("POST", "/api/incidents/" + id + "/approve", copilot, "{}", null).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/incidents/" + id, oncall, null, null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/incidents/" + id, token("kb", "kb:read"), null, null).statusCode()).isEqualTo(403);

        JsonNode approved = json.readTree(send("POST", "/api/incidents/" + id + "/approve", oncall, "{}", null).body());
        assertThat(approved.get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(approved.get("decidedBy").asString()).isEqualTo("oncall.priya");
        assertThat(approved.get("createdBy").asString()).isEqualTo("copilot");
    }

    @Test
    void whoeverDraftedAnIncidentCannotApproveItThemselves() throws Exception {
        assertThat(status("POST", "/api/rca", token("operator", "rca:write"))).isEqualTo(200);
        // One client holding both scopes
        String both = token("oncall.sam", "rca:read incident:write incident:approve incident:read");
        String id = json.readTree(send("POST", "/api/incidents/drafts", both, "{\"rank\":3}", null).body())
                .get("draft").get("id").asString();

        HttpResponse<String> self = send("POST", "/api/incidents/" + id + "/approve", both, "{}", null);

        assertThat(self.statusCode()).isEqualTo(403);
        assertThat(self.body()).contains("was created by 'oncall.sam' and must be approved by someone else");
        assertThat(json.readTree(send("GET", "/api/incidents/" + id, both, null, null).body()).get("status").asString())
                .isEqualTo("DRAFT");
        // Someone else can
        assertThat(json.readTree(send("POST", "/api/incidents/" + id + "/approve",
                token("oncall.priya", "incident:approve"), "{}", null).body()).get("status").asString()).isEqualTo("SUBMITTED");
    }

    @Test
    void mcpNeedsATokenAndEnforcesEachToolsScope() throws Exception {
        assertThat(status("POST", "/api/rca", token("operator", "rca:write"))).isEqualTo(200);
        String list = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

        HttpResponse<String> none = mcp(null, list, null);
        assertThat(none.statusCode()).isEqualTo(401);
        assertThat(none.headers().firstValue("WWW-Authenticate").orElseThrow()).contains("resource_metadata=");

        // A valid token sees the tools; calling one needs that tool's scope
        String kbOnly = token("copilot-kb", "kb:read");
        assertThat(result(mcp(kbOnly, list, null)).get("tools")).hasSize(16);
        JsonNode allowed = result(mcp(kbOnly, call("lookupRunbook", "{\"query\":\"LedgerPostingException\"}"), null));
        assertThat(allowed.path("isError").asBoolean()).isFalse();
        JsonNode denied = result(mcp(kbOnly, call("getFailureSummary", "{}"), null));
        assertThat(denied.get("isError").asBoolean()).isTrue();
        assertThat(denied.get("content").get(0).get("text").asString())
                .contains("Access denied: getFailureSummary needs scope rca:read");
        assertThat(lastAuditLine("copilot-kb", "getFailureSummary")).contains("\"outcome\":\"DENIED\"");

        // The caller and its trace id are on the audit line of an MCP call too
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        HttpResponse<String> traced = mcp(token("copilot", "rca:read"), call("getFailureSummary", "{}"),
                "00-" + traceId + "-b7ad6b7169203331-01");
        assertThat(result(traced).path("isError").asBoolean()).isFalse();
        assertThat(traced.headers().firstValue("X-Trace-Id")).contains(traceId);
        assertThat(lastAuditLine("copilot", "getFailureSummary")).contains("\"traceId\":\"" + traceId + "\"")
                .contains("\"outcome\":\"OK\"");
    }

    @Test
    void answersMcpCallsThatArriveAtTheSameTime() throws Exception {
        assertThat(status("POST", "/api/rca", token("operator", "rca:write"))).isEqualTo(200);
        String token = token("parallel", "rca:read");
        List<java.util.concurrent.CompletableFuture<HttpResponse<String>>> calls = new java.util.ArrayList<>();
        for (int rank = 1; rank <= 4; rank++) {
            calls.add(http.sendAsync(mcpRequest(token, call("getFinding", "{\"rank\":" + rank + "}"), null),
                    HttpResponse.BodyHandlers.ofString()));
        }

        for (int i = 0; i < calls.size(); i++) {
            JsonNode finding = json.readTree(result(calls.get(i).get()).get("content").get(0).get("text").asString());
            assertThat(finding.get("rank").asInt()).isEqualTo(i + 1);
        }
    }

    @Test
    void anAssistantCanProposeAFixButOnlyAPersonCanApproveIt() throws Exception {
        String assistant = token("copilot-fix", "rca:read logs:read code:read change:read investigation:write pr:write");
        String person = token("oncall.lee", "rca:read investigation:approve");
        send("POST", "/api/runs", token("runner", "rca:write"), null, null);

        // Collecting needs every scope of what it reads
        HttpResponse<String> thin = send("POST", "/api/tools/rcaCollectEvidence", token("thin", "investigation:write"),
                "{\"rank\":1}", null);
        assertThat(thin.statusCode()).isEqualTo(403);
        assertThat(thin.body()).contains("rcaCollectEvidence needs scope").contains("logs:read");

        String id = json.readTree(send("POST", "/api/tools/rcaCollectEvidence", assistant, "{\"rank\":1}", null).body())
                .get("id").asString();
        String inv = "{\"investigationId\":\"" + id + "\"";
        send("POST", "/api/tools/rcaRecordHypotheses", assistant, inv + ",\"hypotheses\":[{\"statement\":\"The timeout was "
                + "lowered.\",\"evidenceIds\":[\"E3\",\"E8\"]}]}", null);
        HttpResponse<String> planned = send("POST", "/api/tools/rcaRecordFixPlan", assistant, inv + ",\"hypothesisRank\":1,"
                + "\"plan\":{\"summary\":\"Restore the timeout\",\"steps\":[\"Revert the setting\"],\"rollback\":\"Redeploy\","
                + "\"testPlan\":[\"Withdrawals succeed\"],\"blastRadiusComponents\":[\"withdrawal-p1\"],"
                + "\"blastRadius\":\"Withdrawals\"}}", null);
        assertThat(planned.statusCode()).as(planned.body()).isEqualTo(200);
        assertThat(json.readTree(planned.body()).get("fixPlan").get("proposedBy").asString()).isEqualTo("copilot-fix");

        // The assistant's token cannot approve, and so cannot get a pull request
        assertThat(send("POST", "/api/investigations/" + id + "/fix-plan/approve", assistant, "{}", null).statusCode())
                .isEqualTo(403);
        HttpResponse<String> early = send("POST", "/api/tools/gitDraftPullRequest", assistant, inv + "}", null);
        assertThat(early.statusCode()).isEqualTo(400);
        assertThat(early.body()).contains("not APPROVED");
        // A person's token cannot open pull requests through the tool without pr:write
        assertThat(send("POST", "/api/tools/gitDraftPullRequest", person, inv + "}", null).statusCode()).isEqualTo(403);

        JsonNode approved = json.readTree(send("POST", "/api/investigations/" + id + "/fix-plan/approve", person, "{}", null).body());
        assertThat(approved.get("fixPlan").get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.get("fixPlan").get("decidedBy").asString()).isEqualTo("oncall.lee");
        JsonNode drafted = json.readTree(send("POST", "/api/tools/gitDraftPullRequest", assistant, inv + "}", null).body());
        assertThat(drafted.get("pullRequest").get("requestedBy").asString()).isEqualTo("copilot-fix");

        // Whoever proposes a plan cannot approve it, even with the scope
        String both = token("oncall.both", "rca:read logs:read code:read change:read investigation:write investigation:approve");
        String own = json.readTree(send("POST", "/api/investigations", both, "{\"rank\":1}", null).body()).get("id").asString();
        send("POST", "/api/investigations/" + own + "/hypotheses", both, "{\"hypotheses\":[{\"statement\":\"x\",\"evidenceIds\":[\"E3\"]}]}", null);
        send("POST", "/api/investigations/" + own + "/fix-plan", both, "{\"hypothesisRank\":1,\"plan\":{\"summary\":\"s\","
                + "\"steps\":[\"a\"],\"rollback\":\"r\",\"testPlan\":[\"t\"],\"blastRadiusComponents\":[\"withdrawal-p1\"],"
                + "\"blastRadius\":\"b\"}}", null);
        HttpResponse<String> self = send("POST", "/api/investigations/" + own + "/fix-plan/approve", both, "{}", null);
        assertThat(self.statusCode()).isEqualTo(403);
        assertThat(self.body()).contains("must be approved by someone else");
    }

    private static String call(String tool, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":" + arguments + "}}";
    }

    private HttpRequest mcpRequest(String token, String body, String traceparent) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (traceparent != null) {
            b.header("traceparent", traceparent);
        }
        return b.build();
    }

    private HttpResponse<String> mcp(String token, String body, String traceparent) throws Exception {
        return http.send(mcpRequest(token, body, traceparent), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode result(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String payload = response.body().lines().filter(l -> l.startsWith("data:")).map(l -> l.substring(5)).findFirst()
                .orElse(response.body());
        return json.readTree(payload).get("result");
    }

    private String lastAuditLine(String client, String tool) throws Exception {
        List<String> lines = Files.readAllLines(Path.of("build/test-data/logs/audit.log"));
        return lines.stream().filter(l -> l.contains("\"client\":\"" + client + "\"") && l.contains("\"tool\":\"" + tool + "\""))
                .reduce((a, b) -> b).orElseThrow();
    }
}
