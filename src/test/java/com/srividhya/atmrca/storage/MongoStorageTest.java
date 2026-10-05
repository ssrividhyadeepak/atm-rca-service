package com.srividhya.atmrca.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.client.MongoClient;
import com.srividhya.atmrca.config.RcaProperties;
import com.srividhya.atmrca.failure.FailureBatch.TransactionCount;
import com.srividhya.atmrca.monitor.MonitoringRun;

import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.reverse.TransitionWalker;

/**
 * The MongoDB path against a real mongod, downloaded and started for the test. Where that is
 * not possible (no download access, unsupported platform) the Mongo tests are skipped, not failed.
 */
class MongoStorageTest {

    private static TransitionWalker.ReachedState<RunningMongodProcess> mongod;
    private static String uri;

    @BeforeAll
    static void startMongo() {
        try {
            mongod = Mongod.instance().start(Version.Main.V7_0);
            uri = "mongodb://" + mongod.current().getServerAddress().getHost() + ":"
                    + mongod.current().getServerAddress().getPort();
        } catch (Throwable e) {
            System.out.println("MongoStorageTest: could not start mongod: " + e);
        }
    }

    @AfterAll
    static void stopMongo() {
        if (mongod != null) {
            mongod.close();
        }
    }

    @Test
    void savesAndListsRunsNewestFirst() {
        Assumptions.assumeTrue(uri != null, "mongod could not be started on this machine");
        MongoStorageConfig config = new MongoStorageConfig();
        RcaProperties props = props(uri);
        try (MongoClient client = config.mongoClient(props)) {
            MongoTemplate template = config.mongoTemplate(client, props);
            template.dropCollection(MongoRunStore.COLLECTION);
            MongoRunStore store = new MongoRunStore(template);

            store.save(run("a", "2026-10-04T10:00:00Z"));
            store.save(run("c", "2026-10-04T12:00:00Z"));
            store.save(run("b", "2026-10-04T11:00:00Z"));

            assertThat(store.count()).isEqualTo(3);
            List<MonitoringRun> latest = store.latest(2);
            assertThat(latest).extracting(MonitoringRun::id).containsExactly("c", "b");
            // Round trip: what comes back is what went in
            assertThat(latest.get(0)).isEqualTo(run("c", "2026-10-04T12:00:00Z"));
            assertThat(store.description()).isEqualTo("MongoDB database atm_rca_test");
            store.ping();
            assertThat(template.indexOps(MongoRunStore.COLLECTION).getIndexInfo())
                    .anyMatch(i -> i.getName().startsWith("startedAt"));
        }
    }

    @Test
    void refusesToStartWithoutAUri() {
        assertThatThrownBy(() -> new MongoStorageConfig().mongoClient(props(" ")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MONGODB_URI is not set");
    }

    @Test
    void doesNotEchoCredentialsFromABadUri() {
        assertThatThrownBy(() -> new MongoStorageConfig().mongoClient(props("mongodb://svc:s3cretPw@@bad host/")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not a valid MongoDB connection string")
                .message().doesNotContain("s3cretPw");
        assertThat(props("mongodb://svc:s3cretPw@host/").toString()).doesNotContain("s3cretPw");
    }

    @Test
    void failsFastWhenMongoIsUnreachable() {
        // Nothing listens on port 1; srv lookups and long timeouts are avoided with a plain host
        assertThatThrownBy(() -> new MongoStorageConfig()
                .mongoClient(props("mongodb://svc:s3cretPw@127.0.0.1:1/?serverSelectionTimeoutMS=500")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Could not reach MongoDB at [127.0.0.1:1]")
                .message().doesNotContain("s3cretPw");
    }

    private static RcaProperties props(String mongoUri) {
        return new RcaProperties("mongo", new RcaProperties.Mongo(mongoUri, "atm_rca_test"), null, null, null);
    }

    private static MonitoringRun run(String id, String startedAt) {
        Instant t = Instant.parse(startedAt);
        return new MonitoringRun(id, t, t.plusMillis(250), "MANUAL", "COMPLETED", t.minusSeconds(86400), t,
                "synthetic events", 3, List.of(new TransactionCount("cash-withdrawal", 2),
                        new TransactionCount("POST /v1.0/deposits", 1)), null);
    }
}
