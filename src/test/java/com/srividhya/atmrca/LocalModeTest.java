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
    void aManualRunIsRecordedAndListedNewestFirst() throws Exception {
        JsonNode first = json.readTree(post("/api/runs").body());
        Thread.sleep(5);
        JsonNode second = json.readTree(post("/api/runs").body());

        assertThat(first.get("trigger").asString()).isEqualTo("MANUAL");
        assertThat(first.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(first.get("startedAt").asString()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");

        JsonNode runs = json.readTree(get("/api/runs?limit=2").body());
        assertThat(runs).hasSize(2);
        assertThat(runs.get(0).get("id").asString()).isEqualTo(second.get("id").asString());
        assertThat(runs.get(1).get("id").asString()).isEqualTo(first.get("id").asString());
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
