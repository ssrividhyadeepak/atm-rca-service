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
    void aRunRetrievesTheFailedTransactionsOfTheLastDay() throws Exception {
        JsonNode run = json.readTree(post("/api/runs").body());

        assertThat(run.get("trigger").asString()).isEqualTo("MANUAL");
        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(run.get("source").asString()).isEqualTo("synthetic events (no Splunk)");
        assertThat(run.get("windowFrom").asString()).isEqualTo("2026-10-01T00:00:00Z");
        assertThat(run.get("windowTo").asString()).isEqualTo("2026-10-02T00:00:00Z");
        assertThat(run.get("failedTransactions").asInt()).isEqualTo(137);
        assertThat(run.get("byTransaction").toString()).isEqualTo("[{\"transaction\":\"cash-withdrawal\",\"count\":75},"
                + "{\"transaction\":\"cash-deposit\",\"count\":36},{\"transaction\":\"balance-inquiry\",\"count\":26}]");

        JsonNode runs = json.readTree(get("/api/runs?limit=1").body());
        assertThat(runs.get(0).get("id").asString()).isEqualTo(run.get("id").asString());

        JsonNode monitoring = json.readTree(get("/actuator/health").body()).get("components").get("monitoring");
        assertThat(monitoring.get("status").asString()).isEqualTo("UP");
        assertThat(monitoring.get("details").get("failedTransactions").asInt()).isEqualTo(137);
    }

    @Test
    void failuresEndpointReturnsMaskedTransactionsOfMonitoredTypesOnly() throws Exception {
        JsonNode batch = json.readTree(get("/api/failures").body());

        assertThat(batch.get("total").asInt()).isEqualTo(137);
        assertThat(batch.get("truncated").asBoolean()).isFalse();
        assertThat(batch.get("items")).hasSize(137);
        // receipt-print fails most often in the synthetic day but is not monitored
        assertThat(batch.toString()).doesNotContain("receipt-print");

        JsonNode first = batch.get("items").get(0);
        assertThat(first.get("timestamp").asString()).startsWith("2026-10-01T");
        assertThat(first.get("correlationId").asString()).matches("MSG-[0-9a-f]{12}");
        assertThat(first.get("terminal").asString()).startsWith("ATM-");
        assertThat(first.get("stackTrace").asString()).contains("\tat com.example.bank.");
        // oldest first
        assertThat(first.get("timestamp").asString()).isLessThan(batch.get("items").get(136).get("timestamp").asString());

        // The synthetic events carry a full card number and account numbers; none may come out
        assertThat(batch.toString()).contains("pan=************1111").contains("account=****")
                .doesNotContain("4111111111111111").doesNotContainPattern("account=\\d");
    }

    @Test
    void aShorterWindowReturnsFewerFailures() throws Exception {
        // The host authorization timeouts are clustered between 13:12 and 15:36; the last 6 hours miss them
        JsonNode batch = json.readTree(get("/api/failures?hours=6").body());

        assertThat(batch.get("from").asString()).isEqualTo("2026-10-01T18:00:00Z");
        assertThat(batch.toString()).doesNotContain("HostAuthTimeoutException").contains("LedgerPostingException");
        assertThat(batch.get("total").asInt()).isBetween(1, 60);
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
