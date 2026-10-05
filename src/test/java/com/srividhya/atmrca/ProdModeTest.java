package com.srividhya.atmrca;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.srividhya.atmrca.monitor.MonitoringJob;
import com.srividhya.atmrca.storage.MongoRunStore;
import com.srividhya.atmrca.storage.RunStore;

import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.reverse.TransitionWalker;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The service as it runs with the prod profile: a real mongod started for the test, and a
 * stand-in for Splunk's REST API. The profile selects MongoDB and live Splunk; nothing
 * synthetic may appear. Skipped where a mongod cannot be downloaded and started.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "rca.monitor.enabled=true")
@ActiveProfiles({ "prod" })
@EnabledIf("mongoAvailable")
class ProdModeTest {

    private static TransitionWalker.ReachedState<RunningMongodProcess> mongod;
    private static final FakeSplunk SPLUNK = new FakeSplunk();
    /** Two raw events as Splunk returns them: the event JSON as a string in _raw. */
    private static final String TWO_FAILURES = "{\"results\":[" + result("1790860412.337", "withdrawal-p1",
            "2026-10-01T06:13:32,337-07:00 -- LEVEL: ERROR com.bank.host.HostClient 928202595 -[exec-6] "
                    + "--Q1231-80cc944e-7925-7d33-3799-2694c2a6898a- com.bank.host.HostTimeoutException: timed out for card "
                    + "4111111111111111")
            + "," + result("1790860500", "deposit-p1",
                    "2026-10-01T06:15:00,000-07:00 -- LEVEL: INFO com.bank.deposit.Fallback 928202596 -[exec-7] "
                            + "--Q0457-0e965c5d-52db-bcd0-73f0-4f60bff9071a- In depositFallback with Exception")
            + "]}";

    private static String result(String ts, String container, String message) {
        String event = "{\"@timestamp\":\"2026-10-01T13:13:32.337Z\",\"hostname\":\"node-1.example.net\",\"kubernetes\":"
                + "{\"container_name\":\"" + container + "\",\"namespace_name\":\"prod\",\"pod_name\":\"" + container
                + "-deploy-abc-12345\"},\"level\":\"error\",\"message\":\"" + message + "\"}";
        return "{\"ts\":\"" + ts + "\",\"_raw\":" + new JsonMapper().writeValueAsString(event) + "}";
    }

    static boolean mongoAvailable() {
        if (mongod == null) {
            try {
                mongod = Mongod.instance().start(Version.Main.V7_0);
            } catch (Throwable e) {
                System.out.println("ProdModeTest: could not start mongod: " + e);
                return false;
            }
        }
        return true;
    }

    @DynamicPropertySource
    static void mongo(DynamicPropertyRegistry registry) {
        registry.add("rca.mongo.uri", () -> "mongodb://" + mongod.current().getServerAddress().getHost() + ":"
                + mongod.current().getServerAddress().getPort());
        registry.add("rca.mongo.database", () -> "atm_rca_prodtest");
        registry.add("rca.splunk.base-url", SPLUNK::url);
        registry.add("rca.splunk.token", () -> "splunk-token-for-test");
        registry.add("rca.splunk.index", () -> "atm_app");
        registry.add("rca.monitor.run-on-startup", () -> "false");
        // Far in the future: the job must exist, but not fire during the test
        registry.add("rca.monitor.cron", () -> "0 0 0 1 1 *");
    }

    @LocalServerPort
    int port;

    @Autowired
    RunStore store;

    @Autowired
    MongoTemplate mongo;

    @Autowired
    MonitoringJob job;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = new JsonMapper();

    @Test
    void retrievesFromSplunkAndStoresTheRunInMongo() throws Exception {
        assertThat(store).isInstanceOf(MongoRunStore.class);
        SPLUNK.status = 200;
        SPLUNK.response = TWO_FAILURES;

        JsonNode run = json.readTree(post("/api/runs").body());

        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(run.get("source").asString()).isEqualTo("Splunk " + SPLUNK.url() + " index atm_app");
        assertThat(run.get("failedTransactions").asInt()).isEqualTo(2);
        assertThat(SPLUNK.authorization).isEqualTo("Bearer splunk-token-for-test");
        assertThat(SPLUNK.form.get("search")).startsWith("search index=atm_app \"*exception*\" "
                + "kubernetes.namespace_name=\"prod\" kubernetes.container_name IN (\"withdrawal-*\","
                + "\"deposit-*\",\"balance-*\",\"ui-base-*\")").endsWith("| table ts _raw");

        org.bson.Document stored = mongo.getCollection("monitoring_runs")
                .find(new org.bson.Document("_id", run.get("id").asString())).first();
        assertThat(stored.getInteger("failedTransactions")).isEqualTo(2);
        assertThat(stored.getList("byTransaction", org.bson.Document.class)).hasSize(2);

        JsonNode health = json.readTree(get("/actuator/health").body());
        assertThat(health.get("status").asString()).isEqualTo("UP");
        assertThat(health.get("components").get("storage").get("details").get("store").asString())
                .isEqualTo("MongoDB database atm_rca_prodtest");
    }

    @Test
    void failuresComeFromSplunkMaskedAndNotFromTheSyntheticDay() throws Exception {
        SPLUNK.status = 200;
        SPLUNK.response = TWO_FAILURES;

        JsonNode batch = json.readTree(get("/api/failures").body());

        assertThat(batch.get("total").asInt()).isEqualTo(2);
        JsonNode first = batch.get("items").get(0);
        assertThat(first.get("timestamp").asString()).isEqualTo("2026-10-01T13:13:32.337Z");
        assertThat(first.get("transaction").asString()).isEqualTo("cash-withdrawal");
        assertThat(first.get("component").asString()).isEqualTo("withdrawal-p1");
        assertThat(first.get("pod").asString()).isEqualTo("withdrawal-p1-deploy-abc-12345");
        assertThat(first.get("atmId").asString()).isEqualTo("Q1231");
        assertThat(first.get("traceId").asString()).isEqualTo("80cc944e-7925-7d33-3799-2694c2a6898a");
        assertThat(first.get("exception").asString()).isEqualTo("com.bank.host.HostTimeoutException");
        assertThat(first.get("message").asString()).endsWith("timed out for card ************1111");
        // What the event did not carry is null
        assertThat(batch.get("items").get(1).get("exception").isNull()).isTrue();
        assertThat(batch.get("items").get(1).get("cluster").isNull()).isTrue();
        assertThat(batch.toString()).doesNotContain("example.bank");
    }

    @Test
    void aRunThatCannotUseSplunkIsRecordedAsFailedAndTurnsHealthDown() throws Exception {
        // A Splunk outage. (Rejected credentials are covered in LiveSplunkClientTest: they are
        // final until restart, which would break the other tests sharing this server.)
        SPLUNK.status = 503;

        JsonNode run = json.readTree(post("/api/runs").body());
        job.run();

        assertThat(run.get("status").asString()).isEqualTo("FAILED");
        assertThat(run.get("note").asString()).contains("failed with status 503").doesNotContain("splunk-token");
        assertThat(store.latest(1).get(0).trigger()).isEqualTo("SCHEDULED");
        HttpResponse<String> health = get("/actuator/health");
        assertThat(health.statusCode()).isEqualTo(503);
        assertThat(json.readTree(health.body()).get("components").get("monitoring").get("status").asString())
                .isEqualTo("DOWN");

        // ...and recovers with the next good run
        SPLUNK.status = 200;
        SPLUNK.response = TWO_FAILURES;
        post("/api/runs");
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
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
