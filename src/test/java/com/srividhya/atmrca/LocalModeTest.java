package com.srividhya.atmrca;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import com.mongodb.client.MongoClient;
import com.srividhya.atmrca.storage.InMemoryRunStore;
import com.srividhya.atmrca.storage.RunStore;

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
        assertThat(run.get("source").asString()).isEqualTo("synthetic events (no Splunk)");
        assertThat(run.get("windowFrom").asString()).isEqualTo("2026-10-01T00:00:00Z");
        assertThat(run.get("windowTo").asString()).isEqualTo("2026-10-02T00:00:00Z");
        assertThat(run.get("failedTransactions").asInt()).isEqualTo(149);
        assertThat(run.get("byTransaction").toString()).isEqualTo("[{\"transaction\":\"cash-withdrawal\",\"count\":75},"
                + "{\"transaction\":\"cash-deposit\",\"count\":36},{\"transaction\":\"balance-inquiry\",\"count\":26},"
                + "{\"transaction\":\"atm-ui\",\"count\":12}]");

        JsonNode runs = json.readTree(get("/api/runs?limit=1").body());
        assertThat(runs.get(0).get("id").asString()).isEqualTo(run.get("id").asString());

        JsonNode monitoring = json.readTree(get("/actuator/health").body()).get("components").get("monitoring");
        assertThat(monitoring.get("status").asString()).isEqualTo("UP");
        assertThat(monitoring.get("details").get("failedTransactions").asInt()).isEqualTo(149);
    }

    @Test
    void failuresCarryComponentPodAndTracingMetadata() throws Exception {
        JsonNode batch = json.readTree(get("/api/failures").body());

        assertThat(batch.get("total").asInt()).isEqualTo(149);
        assertThat(batch.get("truncated").asBoolean()).isFalse();
        assertThat(batch.get("unparsed").asInt()).isZero();
        assertThat(batch.get("byComponent").toString()).isEqualTo("[{\"component\":\"app-atm-withdrawal-prod1\",\"count\":75},"
                + "{\"component\":\"app-atm-deposit-prod1\",\"count\":36},{\"component\":\"app-atm-balance-prod1\",\"count\":26},"
                + "{\"component\":\"app-atm-ui-base-prod1\",\"count\":12}]");
        // The receipt printer fails most often in the synthetic day but is not a monitored component
        assertThat(batch.toString()).doesNotContain("app-atm-receipt");

        JsonNode timeout = first(batch, "com.example.bank.withdrawal.host.HostAuthTimeoutException");
        assertThat(timeout.get("transaction").asString()).isEqualTo("cash-withdrawal");
        assertThat(timeout.get("component").asString()).isEqualTo("app-atm-withdrawal-prod1");
        assertThat(timeout.get("pod").asString()).matches("app-atm-withdrawal-prod1-deploy-[0-9a-f]{10}-\\w{5}");
        assertThat(timeout.get("namespace").asString()).isEqualTo("atm-prod");
        assertThat(timeout.get("cluster").asString()).isEqualTo("east1");
        assertThat(timeout.get("host").asString()).matches("ocp-node-\\d+\\.example\\.net");
        assertThat(timeout.get("level").asString()).isEqualTo("ERROR");
        assertThat(timeout.get("logger").asString()).isEqualTo("com.example.bank.withdrawal.host.HostAuthClient");
        assertThat(timeout.get("thread").asString()).startsWith("http-nio-8080-exec-");
        assertThat(timeout.get("atmId").asString()).matches("[0-9]{4}[A-Z]");
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

        // A UI event: ATM id and tracking session from the text, no trace id
        JsonNode ui = firstWhere(batch, "component", "app-atm-ui-base-prod1");
        assertThat(ui.get("transaction").asString()).isEqualTo("atm-ui");
        assertThat(ui.get("atmId").asString()).matches("[0-9]{4}[A-Z]");
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
        assertThat(batch.get("total").asInt()).isBetween(1, 70);
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
    void onlyHealthIsExposedFromTheActuator() throws Exception {
        assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
        assertThat(get("/actuator/beans").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
}
