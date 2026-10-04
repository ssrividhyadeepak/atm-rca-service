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
 * The service as it runs with the prod profile, against a real mongod started for the test:
 * the profile selects MongoDB, runs are stored there, and health reports it.
 * Skipped where a mongod cannot be downloaded and started.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "rca.monitor.enabled=true")
@ActiveProfiles({ "prod" })
@EnabledIf("mongoAvailable")
class ProdModeTest {

    private static TransitionWalker.ReachedState<RunningMongodProcess> mongod;

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
    void storesRunsInMongoAndReportsItInHealth() throws Exception {
        assertThat(store).isInstanceOf(MongoRunStore.class);
        long before = mongo.getCollection("monitoring_runs").countDocuments();

        JsonNode run = json.readTree(http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/runs"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).body());
        job.run();

        assertThat(mongo.getCollection("monitoring_runs").countDocuments()).isEqualTo(before + 2);
        assertThat(mongo.getCollection("monitoring_runs").find(new org.bson.Document("_id", run.get("id").asString()))
                .first().getString("trigger")).isEqualTo("MANUAL");
        assertThat(store.latest(1).get(0).trigger()).isEqualTo("SCHEDULED");

        JsonNode health = json.readTree(http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString()).body());
        assertThat(health.get("status").asString()).isEqualTo("UP");
        assertThat(health.get("components").get("storage").get("details").get("store").asString())
                .isEqualTo("MongoDB database atm_rca_prodtest");
    }
}
