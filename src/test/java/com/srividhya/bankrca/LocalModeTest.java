package com.srividhya.bankrca;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import com.mongodb.client.MongoClient;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.srividhya.bankrca.storage.InMemoryRunStore;
import com.srividhya.bankrca.storage.RunStore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The service as it runs with no profile: real HTTP server, in-memory storage, no MongoDB. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LocalModeTest {

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext context;

    @Autowired
    RunStore store;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = new JsonMapper();

    @Test
    void usesInMemoryStorageAndCreatesNoMongoClient() {
        assertThat(store).isInstanceOf(InMemoryRunStore.class);
        assertThat(context.getBeanNamesForType(MongoClient.class)).isEmpty();
    }

    @Test
    void healthIsUpAndNamesTheStore() throws Exception {
        HttpResponse<String> r = get("/actuator/health");

        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode health = json.readTree(r.body());
        assertThat(health.get("status").asString()).isEqualTo("UP");
        JsonNode storage = health.get("components").get("storage");
        assertThat(storage.get("status").asString()).isEqualTo("UP");
        assertThat(storage.get("details").get("store").asString()).isEqualTo("in-memory (lost on restart)");
    }

    @Test
    void aRunRetrievesTheFailureEventsOfTheLastDay() throws Exception {
        JsonNode run = json.readTree(post("/api/runs").body());

        assertThat(run.get("trigger").asString()).isEqualTo("MANUAL");
        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(run.get("source").asString()).startsWith("synthetic events from ");
        assertThat(run.get("windowFrom").asString()).isEqualTo("2026-10-01T00:00:00Z");
        assertThat(run.get("windowTo").asString()).isEqualTo("2026-10-02T00:00:00Z");
        assertThat(run.get("failedTransactions").asInt()).isEqualTo(163);
        assertThat(run.get("byTransaction").toString()).isEqualTo("[{\"transaction\":\"cash-withdrawal\",\"count\":75},"
                + "{\"transaction\":\"cash-deposit\",\"count\":36},{\"transaction\":\"balance-inquiry\",\"count\":26},"
                + "{\"transaction\":\"gateway\",\"count\":14},{\"transaction\":\"ui\",\"count\":12}]");
        assertThat(run.get("signatures").asInt()).isEqualTo(8);
        assertThat(run.get("topSignature").asString()).isEqualTo("withdrawal-p1 HostAuthTimeoutException x64 (BURST)");

        // The clock is fixed in tests, so every run has the same start time: check it is listed, not that it is first
        assertThat(get("/api/runs?limit=100").body()).contains(run.get("id").asString());

        JsonNode monitoring = json.readTree(get("/actuator/health").body()).get("components").get("monitoring");
        assertThat(monitoring.get("status").asString()).isEqualTo("UP");
        assertThat(monitoring.get("details").get("failedTransactions").asInt()).isEqualTo(163);
    }

    @Test
    void failuresCarryComponentPodAndTracingMetadata() throws Exception {
        JsonNode batch = json.readTree(get("/api/failures").body());

        assertThat(batch.get("total").asInt()).isEqualTo(163);
        assertThat(batch.get("truncated").asBoolean()).isFalse();
        assertThat(batch.get("unparsed").asInt()).isZero();
        assertThat(batch.get("byComponent").toString()).isEqualTo("[{\"component\":\"withdrawal-p1\",\"count\":75},"
                + "{\"component\":\"deposit-p1\",\"count\":36},{\"component\":\"balance-p1\",\"count\":26},"
                + "{\"component\":\"gateway-p1\",\"count\":14},{\"component\":\"ui-base-p1\",\"count\":12}]");
        // The receipt printer fails most often in the synthetic day but is not a monitored component
        assertThat(batch.toString()).doesNotContain("receipt");

        JsonNode timeout = first(batch, "com.example.bank.withdrawal.host.HostAuthTimeoutException");
        assertThat(timeout.get("transaction").asString()).isEqualTo("cash-withdrawal");
        assertThat(timeout.get("component").asString()).isEqualTo("withdrawal-p1");
        assertThat(timeout.get("pod").asString()).matches("withdrawal-p1-deploy-[0-9a-f]{10}-\\w{5}");
        assertThat(timeout.get("namespace").asString()).isEqualTo("prod");
        assertThat(timeout.get("cluster").asString()).isEqualTo("east1");
        assertThat(timeout.get("host").asString()).matches("ocp-node-\\d+\\.example\\.net");
        assertThat(timeout.get("level").asString()).isEqualTo("ERROR");
        assertThat(timeout.get("logger").asString()).isEqualTo("com.example.bank.withdrawal.host.HostAuthClient");
        assertThat(timeout.get("thread").asString()).startsWith("http-nio-8080-exec-");
        assertThat(timeout.get("bankId").asString()).matches("[A-Z][0-9]{4}");
        assertThat(timeout.get("traceId").asString()).matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
        assertThat(timeout.get("timestamp").asString()).startsWith("2026-10-01T");
        assertThat(timeout.get("message").asString()).endsWith("host authorization timed out after 500ms (attempt 2/2)");
        assertThat(timeout.get("stackTrace").asString()).contains("\tat com.example.bank.withdrawal.host.HostAuthClient.authorize")
                .contains("Caused by: java.net.SocketTimeoutException");
    }

    @Test
    void eventsWithoutAJavaExceptionAreKeptWithTheirMetadata() throws Exception {
        JsonNode batch = json.readTree(get("/api/failures").body());

        // A fallback that logs at INFO: trace id, no exception class, no stack trace
        JsonNode fallback = firstWhere(batch, "message", "In downstreamBalanceFallback with Exception");
        assertThat(fallback.get("level").asString()).isEqualTo("INFO");
        assertThat(fallback.get("traceId").isNull()).isFalse();
        assertThat(fallback.get("exception").isNull()).isTrue();
        assertThat(fallback.get("stackTrace").isNull()).isTrue();

        // A UI event: bank id and tracking session from the text, no trace id
        JsonNode ui = firstWhere(batch, "component", "ui-base-p1");
        assertThat(ui.get("transaction").asString()).isEqualTo("ui");
        assertThat(ui.get("bankId").asString()).matches("[A-Z][0-9]{4}");
        assertThat(ui.get("sessionId").asString()).matches("[0-9A-F]{32}");
        assertThat(ui.get("traceId").isNull()).isTrue();
    }

    @Test
    void masksCardAndAccountNumbers() throws Exception {
        String batch = get("/api/failures").body();

        // The synthetic events carry a full card number and account numbers; none may come out
        assertThat(batch).contains("pan=************1111").contains("account=****")
                .doesNotContain("4111111111111111").doesNotContainPattern("account=\\d");
    }

    @Test
    void aShorterWindowReturnsFewerFailures() throws Exception {
        // The host authorization timeouts are clustered between 13:12 and 15:36; the last 6 hours miss them
        JsonNode batch = json.readTree(get("/api/failures?hours=6").body());

        assertThat(batch.get("from").asString()).isEqualTo("2026-10-01T18:00:00Z");
        assertThat(batch.toString()).doesNotContain("HostAuthTimeoutException").contains("LedgerPostingException");
        assertThat(batch.get("total").asInt()).isBetween(1, 80);
    }

    @Test
    void correlationTurnsTheDayIntoEightDistinctProblems() throws Exception {
        JsonNode c = json.readTree(get("/api/correlation").body());

        assertThat(c.get("totalEvents").asInt()).isEqualTo(163);
        assertThat(c.get("signatures")).hasSize(8);
        List<String> summary = new ArrayList<>();
        for (JsonNode s : c.get("signatures")) {
            String exception = s.get("exception").isNull() ? "-" : s.get("exception").asString();
            summary.add(s.get("count").asInt() + " " + s.get("component").asString() + " "
                    + exception.substring(exception.lastIndexOf('.') + 1) + " " + s.get("timePattern").asString());
        }
        assertThat(summary).containsExactly(
                "64 withdrawal-p1 HostAuthTimeoutException BURST",
                "22 deposit-p1 NullPointerException STEADY",
                "17 balance-p1 CoreBankingTimeoutException BURST",
                "14 deposit-p1 LedgerPostingException BURST",
                "14 gateway-p1 - BURST",
                "12 ui-base-p1 - STEADY",
                "11 withdrawal-p1 InsufficientCassetteException STEADY",
                "9 balance-p1 - STEADY");

        JsonNode timeout = c.get("signatures").get(0);
        assertThat(timeout.get("percentOfComponent").asDouble()).isEqualTo(85.3);
        assertThat(timeout.get("firstSeen").asString()).isBetween("2026-10-01T13:12", "2026-10-01T13:40");
        assertThat(timeout.get("lastSeen").asString()).isBetween("2026-10-01T15:10", "2026-10-01T15:36");
        assertThat(timeout.get("pods").asInt()).isEqualTo(2);
        assertThat(timeout.get("sampleTraceIds")).hasSize(3);
        assertThat(timeout.get("sampleStackTrace").asString()).contains("Caused by: java.net.SocketTimeoutException");
        // Masked values never reach the grouped output either
        assertThat(c.toString()).doesNotContain("4111111111111111").doesNotContainPattern("account=\\d");

        // Every ledger failure was also reported by the gateway, under the same trace id
        assertThat(c.get("chains")).hasSize(1);
        assertThat(c.get("chains").get(0).get("components").toString()).isEqualTo("[\"deposit-p1\",\"gateway-p1\"]");
        assertThat(c.get("chains").get(0).get("traces").asInt()).isEqualTo(14);
    }

    private static JsonNode first(JsonNode batch, String exception) {
        return firstWhere(batch, "exception", exception);
    }

    private static JsonNode firstWhere(JsonNode batch, String field, String value) {
        for (JsonNode item : batch.get("items")) {
            if (value.equals(item.path(field).asString(null))) {
                return item;
            }
        }
        throw new AssertionError("No item with " + field + "=" + value);
    }

    @Test
    void producesTheRcaReportForTheDay() throws Exception {
        JsonNode report = json.readTree(post("/api/rca").body());

        assertThat(report.get("id").asString()).isEqualTo("2026-10-02");
        assertThat(report.get("analyzer").asString()).isEqualTo("rule-based/v1");
        assertThat(report.get("totalEvents").asInt()).isEqualTo(163);
        assertThat(report.get("headline").asString()).startsWith("8 distinct problems in 163 failure events")
                .endsWith("Look first at: HostAuthTimeoutException in withdrawal-p1; NullPointerException in deposit-p1.");
        List<String> summary = new ArrayList<>();
        for (JsonNode f : report.get("findings")) {
            summary.add(f.get("rank").asInt() + " " + f.get("severity").asString() + " " + f.get("category").asString()
                    + " " + f.get("component").asString() + " " + f.get("count").asInt());
        }
        assertThat(summary).containsExactly(
                "1 HIGH DOWNSTREAM_TIMEOUT withdrawal-p1 64",
                "2 HIGH CODE_DEFECT deposit-p1 22",
                "3 MEDIUM DOWNSTREAM_TIMEOUT balance-p1 17",
                "4 MEDIUM DOWNSTREAM_UNAVAILABLE deposit-p1 14",
                "5 LOW PROPAGATED gateway-p1 14",
                "6 LOW CLIENT_UI ui-base-p1 12",
                "7 LOW EXPECTED_CONDITION withdrawal-p1 11",
                "8 LOW HANDLED_FALLBACK balance-p1 9");
        JsonNode defect = report.get("findings").get(1);
        assertThat(defect.get("location").asString())
                .isEqualTo("com.example.bank.deposit.validation.DepositValidator.validate(DepositValidator.java:35)");
        assertThat(report.get("findings").get(0).get("location").asString())
                .isEqualTo("com.example.bank.withdrawal.host.HostAuthClient.call(HostAuthClient.java:73)");
        assertThat(report.get("findings").get(4).get("title").asString())
                .isEqualTo("gateway-p1 reports failures that started in deposit-p1");

        // Checked against the (sample) source: the timeout's file was changed shortly before the burst...
        JsonNode timeout = report.get("findings").get(0);
        assertThat(timeout.get("suspectCommit").asString()).isEqualTo("a9aa3e8f10");
        assertThat(timeout.get("rules").toString()).contains("R10-file-recently-changed");
        assertThat(timeout.get("likelyCause").asString())
                .contains("alex.ng \"withdrawal: lower host auth timeout for faster failover\"");
        assertThat(timeout.get("source").get("code").asString())
                .isEqualTo("return http.post(endpoint + \"/authorize\", request, TIMEOUT_MS);");
        // ...and the null pointer is on a line changed a few days ago
        assertThat(defect.get("suspectCommit").asString()).isEqualTo("a9559dfcfa");
        assertThat(defect.get("rules").toString()).contains("R11-line-recently-changed");
        assertThat(defect.get("suggestedAction").asString()).startsWith("Review commit a9559dfcfa first.");
        // The runbook and the past RCA written for the same exception are attached
        assertThat(timeout.get("knowledge").get(0).get("id").asString()).isEqualTo("RB-001");
        assertThat(timeout.get("knowledge").get(0).get("matchedBy").asString()).isEqualTo("EXACT");
        assertThat(timeout.get("knowledge").get(1).get("id").asString()).isEqualTo("RCA-2026-03-14");
        assertThat(timeout.get("suggestedAction").asString()).contains("Runbook RB-001: Roll back the change to the timeout setting");
        assertThat(defect.get("knowledge").get(0).get("id").asString()).isEqualTo("RB-004");
        // Nothing is attached to a failure that only repeats another component's
        assertThat(report.get("findings").get(4).get("knowledge")).isEmpty();
        // Old code is not blamed
        assertThat(report.get("findings").get(2).get("suspectCommit").isNull()).isTrue();
        assertThat(report.get("findings").get(2).get("source").get("found").asBoolean()).isTrue();

        // The saved report is what the endpoints return
        assertThat(json.readTree(get("/api/rca/latest").body()).get("id").asString()).isEqualTo("2026-10-02");
        HttpResponse<String> markdown = get("/api/rca/latest.md");
        assertThat(markdown.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/markdown");
        assertThat(markdown.body()).startsWith("# Failure RCA: 2026-10-01T00:00:00Z to 2026-10-02T00:00:00Z")
                .contains("## 2. NullPointerException in deposit-p1").doesNotContain("4111111111111111");
        assertThat(json.readTree(get("/api/rca").body())).hasSize(1);
    }

    @Test
    void theDailyRcaInputMatchesItsPublishedSchema() throws Exception {
        JsonNode report = json.readTree(post("/api/rca").body());
        String input = get("/api/rca/input").body();
        String schema = get("/api/rca/input/schema").body();

        Schema validator = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schema);
        assertThat(validator.validate(input, InputFormat.JSON)).as("schema violations").isEmpty();

        // The schema is strict: a field it does not know, or a missing one, is a violation.
        // That is what makes this test fail when the record changes and the schema does not.
        assertThat(validator.validate(input.replaceFirst("\\{", "{\"surprise\":1,"), InputFormat.JSON)).isNotEmpty();
        assertThat(validator.validate(input.replace("\"timePattern\"", "\"timePatern\""), InputFormat.JSON)).isNotEmpty();
        assertThat(validator.validate(input.replace("\"schemaVersion\" : \"1.2\"", "\"schemaVersion\" : \"9\""),
                InputFormat.JSON)).isNotEmpty();

        JsonNode parsed = json.readTree(input);
        assertThat(parsed.get("id").asString()).isEqualTo("2026-10-02");
        assertThat(parsed.get("schemaVersion").asString()).isEqualTo("1.2");
        // Five of the eight signatures have a stack trace, and each was looked up in the sample source
        assertThat(parsed.get("sources")).hasSize(5);
        assertThat(parsed.get("sources").toString()).doesNotContain("\"found\":false");
        assertThat(parsed.get("omittedSignatures").asInt()).isZero();
        assertThat(parsed.get("correlation").get("signatures")).hasSize(8);
        // Sanitized: masked values only, and stack traces cut to exception lines and application frames
        assertThat(input).doesNotContain("4111111111111111").doesNotContainPattern("account=\\d");
        String stack = parsed.get("correlation").get("signatures").get(0).get("sampleStackTrace").asString();
        assertThat(stack).contains("HostAuthClient.authorize(HostAuthClient.java:58)")
                .contains("Caused by: java.net.SocketTimeoutException").contains("... 1 other frame")
                .doesNotContain("org.springframework");

        // The report names the input it was built from
        assertThat(report.get("inputSchemaVersion").asString()).isEqualTo("1.2");
        assertThat(report.get("inputHash").asString()).matches("[0-9a-f]{64}");
    }

    @Test
    void replaysAnInputWithoutReadingSplunkOrSavingAnything() throws Exception {
        JsonNode original = json.readTree(post("/api/rca").body());
        String input = get("/api/rca/input").body();

        // Take the NullPointerException out by hand and analyze what is left
        tools.jackson.databind.node.ObjectNode edited = (tools.jackson.databind.node.ObjectNode) json.readTree(input);
        tools.jackson.databind.node.ArrayNode signatures = (tools.jackson.databind.node.ArrayNode) edited
                .get("correlation").get("signatures");
        signatures.remove(1);
        HttpResponse<String> replayed = postJson("/api/rca/replay", edited.toString());

        assertThat(replayed.statusCode()).isEqualTo(200);
        JsonNode report = json.readTree(replayed.body());
        assertThat(report.get("findings")).hasSize(7);
        assertThat(report.toString()).doesNotContain("NullPointerException");
        assertThat(report.get("inputHash").isNull()).as("a replay is not tied to a saved input").isTrue();
        // The saved report of the day is untouched
        assertThat(json.readTree(get("/api/rca/latest").body()).get("findings")).hasSize(original.get("findings").size());

        HttpResponse<String> wrongVersion = postJson("/api/rca/replay", input.replace("\"1.2\"", "\"7.3\""));
        assertThat(wrongVersion.statusCode()).isEqualTo(400);
        assertThat(wrongVersion.body()).contains("Unsupported schemaVersion '7.3'; this service reads 1.0 and 1.1 and 1.2");
        assertThat(postJson("/api/rca/replay", "{\"schemaVersion\":\"1.0\"}").statusCode()).isEqualTo(400);
    }

    @Test
    void locatesAPastedStackTraceInTheSource() throws Exception {
        HttpResponse<String> found = postText("/api/source/locate?component=deposit-p1",
                "java.lang.IllegalStateException: boom\n\tat com.example.bank.deposit.validation.DepositValidator."
                        + "validate(DepositValidator.java:35)\n\tat org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:885)");

        assertThat(found.statusCode()).isEqualTo(200);
        JsonNode at = json.readTree(found.body());
        assertThat(at.get("found").asBoolean()).isTrue();
        assertThat(at.get("path").asString()).endsWith("deposit/validation/DepositValidator.java");
        assertThat(at.get("code").asString()).isEqualTo("String currency = envelope.getCurrency();");
        assertThat(at.get("snippet").asString()).contains(">   35 |");

        JsonNode missing = json.readTree(postText("/api/source/locate", "x\n\tat com.example.bank.nowhere.Missing.run(Missing.java:9)").body());
        assertThat(missing.get("found").asBoolean()).isFalse();
        assertThat(missing.get("note").asString()).contains("com.example.bank.nowhere.Missing is not in");

        HttpResponse<String> noFrame = postText("/api/source/locate", "it just broke");
        assertThat(noFrame.statusCode()).isEqualTo(400);
        assertThat(noFrame.body()).contains("No application frame found");
    }

    @Test
    void searchesRunbooksAndPastRcas() throws Exception {
        JsonNode status = json.readTree(get("/api/knowledge").body());
        assertThat(status.get("runbooks").asInt()).isEqualTo(6);
        assertThat(status.get("pastRcas").asInt()).isGreaterThanOrEqualTo(5);
        assertThat(status.get("search").asString()).isEqualTo("keyword");

        JsonNode exact = json.readTree(get("/api/knowledge/search?type=runbook&q=LedgerPostingException").body());
        assertThat(exact.get(0).get("id").asString()).isEqualTo("RB-002");
        assertThat(exact.get(0).get("matchedBy").asString()).isEqualTo("EXACT");
        assertThat(exact.get(0).get("summary").asString()).startsWith("Nothing to do inside the maintenance window");

        JsonNode words = json.readTree(get("/api/knowledge/search?type=past-rca&q=firewall%20change%20added%20latency"
                + "%20to%20the%20authorization%20gateway").body());
        assertThat(words.get(0).get("id").asString()).isEqualTo("RCA-2026-03-14");
        assertThat(words.get(0).get("matchedBy").asString()).isEqualTo("SEMANTIC");
        assertThat(words.get(0).get("related").asBoolean()).isTrue();
        assertThat(words.get(0).get("matchedPassage").asString()).contains("firewall");

        assertThat(get("/api/knowledge/search?type=nonsense&q=x").statusCode()).isEqualTo(400);
        assertThat(get("/api/knowledge/search?type=runbook&q=%20").statusCode()).isEqualTo(400);
        assertThat(json.readTree(post("/api/knowledge/reload").body()).get("runbooks").asInt()).isEqualTo(6);
    }

    @Test
    void listsTheToolsWithTheirSchemas() throws Exception {
        JsonNode tools = json.readTree(get("/api/tools").body());

        assertThat(tools).extracting(t -> t.get("name").asString())
                .containsExactly("getFailureSummary", "getFinding", "lookupRunbook", "searchHistoricalRca");
        JsonNode lookup = tools.get(2);
        assertThat(lookup.get("description").asString()).startsWith("Find the runbook for a problem.").contains("NO_MATCH");
        // Input schema, generated from the method signature: what a model is given to fill in
        assertThat(lookup.get("inputSchema").get("type").asString()).isEqualTo("object");
        assertThat(lookup.get("inputSchema").get("properties").get("query").get("type").asString()).isEqualTo("string");
        assertThat(lookup.get("inputSchema").get("properties").get("query").get("description").asString())
                .contains("at most 500 characters");
        assertThat(lookup.get("inputSchema").get("properties").get("limit").get("type").asString()).isEqualTo("integer");
        assertThat(lookup.get("inputSchema").get("required").toString()).isEqualTo("[\"query\"]");
        assertThat(lookup.get("outputSchema").get("properties").has("verdict")).isTrue();
        assertThat(tools.get(1).get("inputSchema").get("required").toString()).isEqualTo("[\"rank\"]");
        assertThat(tools.get(0).get("stats").has("calls")).isTrue();
    }

    @Test
    void callsAToolByNameWithJsonArguments() throws Exception {
        post("/api/rca");

        JsonNode summary = json.readTree(postJson("/api/tools/getFailureSummary", "{}").body());
        assertThat(summary.get("reportId").asString()).isEqualTo("2026-10-02");
        assertThat(summary.get("totalEvents").asInt()).isEqualTo(163);
        assertThat(summary.get("findings")).hasSize(8);
        assertThat(summary.get("findings").get(0).toString()).isEqualTo("{\"rank\":1,\"severity\":\"HIGH\",\"status\":\"NEW\","
                + "\"category\":\"DOWNSTREAM_TIMEOUT\",\"component\":\"withdrawal-p1\",\"problem\":\"HostAuthTimeoutException\","
                + "\"count\":64,\"timePattern\":\"BURST\",\"suspectCommit\":\"a9aa3e8f10\",\"runbook\":\"RB-001\"}");

        JsonNode finding = json.readTree(postJson("/api/tools/getFinding", "{\"rank\":2}").body());
        assertThat(finding.get("title").asString()).isEqualTo("NullPointerException in deposit-p1");
        assertThat(finding.get("source").get("line").asInt()).isEqualTo(35);

        JsonNode runbook = json.readTree(postJson("/api/tools/lookupRunbook", "{\"query\":\"LedgerPostingException\",\"limit\":1}").body());
        assertThat(runbook.get("verdict").asString()).isEqualTo("MATCH");
        assertThat(runbook.get("hits")).hasSize(1);
        assertThat(runbook.get("hits").get(0).get("id").asString()).isEqualTo("RB-002");

        // The past, not today's own report
        JsonNode history = json.readTree(postJson("/api/tools/searchHistoricalRca", "{\"query\":\"HostAuthTimeoutException\"}").body());
        assertThat(history.get("verdict").asString()).isEqualTo("MATCH");
        assertThat(history.get("hits").get(0).get("id").asString()).isEqualTo("RCA-2026-03-14");
        assertThat(history.toString()).doesNotContain("RCA-2026-10-02");

        JsonNode nothing = json.readTree(postJson("/api/tools/lookupRunbook", "{\"query\":\"the log volume filled the disk\"}").body());
        assertThat(nothing.get("verdict").asString()).isEqualTo("NO_MATCH");
    }

    @Test
    void refusesBadToolCallsWithAMessageThatSaysWhatToSend() throws Exception {
        post("/api/rca");

        HttpResponse<String> rank = postJson("/api/tools/getFinding", "{\"rank\":99}");
        assertThat(rank.statusCode()).isEqualTo(400);
        assertThat(rank.body()).contains("'rank' must be between 1 and 8");
        assertThat(postJson("/api/tools/getFinding", "{}").body()).contains("'rank' must be between 1 and 8");
        assertThat(postJson("/api/tools/lookupRunbook", "{\"limit\":1}").body()).contains("'query' is required");
        assertThat(postJson("/api/tools/lookupRunbook", "{\"query\":\"x\",\"limit\":50}").body()).contains("'limit' must be between 1 and 5");
        assertThat(postJson("/api/tools/lookupRunbook", "{\"query\":\"" + "x".repeat(501) + "\"}").body()).contains("too long");

        HttpResponse<String> wrongType = postJson("/api/tools/getFinding", "{\"rank\":\"two\"}");
        assertThat(wrongType.statusCode()).isEqualTo(400);
        assertThat(wrongType.body()).contains("do not fit the tool's input schema");
        assertThat(postJson("/api/tools/getFinding", "[1]").statusCode()).isEqualTo(400);
        assertThat(postJson("/api/tools/getFinding", "{not json").body()).contains("not valid JSON");

        HttpResponse<String> unknown = postJson("/api/tools/dropTables", "{}");
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).contains("There is no tool 'dropTables'").contains("getFailureSummary");
    }

    @Test
    void recordsEveryToolCallInTheAuditLog() throws Exception {
        post("/api/rca");
        postJson("/api/tools/lookupRunbook", "{\"query\":\"card 4111111111111111 was refused\"}");
        postJson("/api/tools/getFinding", "{\"rank\":99}");

        List<String> lines = java.nio.file.Files.readAllLines(java.nio.file.Path.of("build/test-data/logs/audit.log"));
        String refused = lines.get(lines.size() - 1);
        String ok = lines.get(lines.size() - 2);
        assertThat(ok).contains("\"tool\":\"lookupRunbook\"").contains("\"outcome\":\"OK\"").contains("\"durationMs\":")
                .containsPattern("\"callId\":\"[0-9a-f]{8}\"")
                // Arguments are masked before they are written
                .contains("card ************1111 was refused").doesNotContain("4111111111111111");
        assertThat(refused).contains("\"tool\":\"getFinding\"").contains("\"outcome\":\"REJECTED\"")
                .contains("\"error\":\"'rank' must be between 1 and 8");

        // The same calls are counted
        JsonNode stats = json.readTree(get("/api/tools").body()).get(1).get("stats");
        assertThat(stats.get("rejected").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(stats.get("calls").asInt()).isEqualTo(stats.get("ok").asInt() + stats.get("rejected").asInt()
                + stats.get("errors").asInt());
    }

    @Test
    void theAssistantAnswersByCallingTools() throws Exception {
        post("/api/rca");

        JsonNode today = json.readTree(postJson("/api/assistant/ask", "{\"question\":\"What failed today?\"}").body());
        assertThat(today.get("model").asString()).isEqualTo("scripted (no LLM configured)");
        assertThat(today.get("complete").asBoolean()).isTrue();
        assertThat(today.get("toolCalls").toString()).isEqualTo("[{\"tool\":\"getFailureSummary\",\"arguments\":\"{}\"}]");
        assertThat(today.get("answer").asString()).startsWith("8 distinct problems in 163 failure events")
                .contains("1. HIGH HostAuthTimeoutException in withdrawal-p1 (64 events, BURST).");

        JsonNode both = json.readTree(postJson("/api/assistant/ask",
                "{\"question\":\"Is there a runbook for HostAuthTimeoutException, and has it happened before?\"}").body());
        assertThat(both.get("toolCalls")).extracting(t -> t.get("tool").asString())
                .containsExactly("lookupRunbook", "searchHistoricalRca");
        assertThat(both.get("answer").asString()).contains("Runbook RB-001").contains("Past RCA RCA-2026-03-14");

        JsonNode none = json.readTree(postJson("/api/assistant/ask", "{\"question\":\"How do I fix a full disk?\"}").body());
        assertThat(none.get("answer").asString()).isEqualTo("There is no runbook for this.");

        assertThat(postJson("/api/assistant/ask", "{\"question\":\" \"}").statusCode()).isEqualTo(400);
        assertThat(postJson("/api/assistant/ask", "{\"question\":\"" + "x".repeat(1001) + "\"}").body()).contains("too long");
    }

    @Test
    void servesTheToolsOverMcp() throws Exception {
        post("/api/rca");

        JsonNode init = mcp("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                + "\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}");
        assertThat(init.get("serverInfo").get("name").asString()).isEqualTo("bank-rca-service");
        assertThat(init.get("instructions").asString()).contains("Start with getFailureSummary").contains("NO_MATCH");

        JsonNode tools = mcp("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").get("tools");
        assertThat(tools).extracting(t -> t.get("name").asString()).containsExactlyInAnyOrder("getFailureSummary",
                "getFinding", "lookupRunbook", "searchHistoricalRca");
        for (JsonNode t : tools) {
            assertThat(t.get("description").asString()).isNotBlank();
            assertThat(t.get("inputSchema").get("type").asString()).isEqualTo("object");
        }

        JsonNode summary = mcp(mcpCall("getFailureSummary", "{}"));
        assertThat(summary.path("isError").asBoolean()).isFalse();
        JsonNode data = json.readTree(summary.get("content").get(0).get("text").asString());
        assertThat(data.get("totalEvents").asInt()).isEqualTo(163);
        assertThat(data.get("findings")).hasSize(8);

        JsonNode runbook = json.readTree(mcp(mcpCall("lookupRunbook", "{\"query\":\"HostAuthTimeoutException\",\"limit\":1}"))
                .get("content").get(0).get("text").asString());
        assertThat(runbook.get("verdict").asString()).isEqualTo("MATCH");
        assertThat(runbook.get("hits").get(0).get("id").asString()).isEqualTo("RB-001");

        // A refused call comes back as a tool error the model can read and correct, not as a protocol failure
        JsonNode refused = mcp(mcpCall("getFinding", "{\"rank\":99}"));
        assertThat(refused.get("isError").asBoolean()).isTrue();
        assertThat(refused.get("content").get(0).get("text").asString()).contains("'rank' must be between 1 and 8");
    }

    @Test
    void offersTheDailyBriefingPromptOverMcp() throws Exception {
        JsonNode prompts = mcp("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"prompts/list\"}").get("prompts");
        assertThat(prompts).extracting(p -> p.get("name").asString()).containsExactly("daily_rca");

        JsonNode prompt = mcp("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"prompts/get\",\"params\":{\"name\":\"daily_rca\","
                + "\"arguments\":{}}}");
        assertThat(prompt.get("description").asString()).isEqualTo("Daily failure briefing (daily-rca/v1)");
        assertThat(prompt.get("messages").get(0).get("content").get("text").asString())
                .startsWith("Prepare today's failure briefing").contains("getFailureSummary").contains("getFinding");
    }

    private static String mcpCall(String tool, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":" + arguments + "}}";
    }

    /** The JSON-RPC result, whether the server answered with plain JSON or a one-event stream. */
    private JsonNode mcp(String body) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String payload = response.body().lines().filter(l -> l.startsWith("data:")).map(l -> l.substring(5)).findFirst()
                .orElse(response.body());
        return json.readTree(payload).get("result");
    }

    @Test
    void aMonitoringRunAlsoWritesTheReport() throws Exception {
        post("/api/runs");

        assertThat(get("/api/rca/latest").statusCode()).isEqualTo(200);
    }

    @Test
    void onlyHealthIsExposedFromTheActuator() throws Exception {
        assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
        assertThat(get("/actuator/beans").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postText(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
}
