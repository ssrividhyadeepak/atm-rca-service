package com.srividhya.atmrca.storage;

import java.util.List;

import org.bson.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import com.srividhya.atmrca.monitor.MonitoringRun;

/** Enterprise storage: one document per run in the monitoring_runs collection. */
@Component
@ConditionalOnProperty(name = "rca.storage", havingValue = "mongo")
public class MongoRunStore implements RunStore {

    static final String COLLECTION = "monitoring_runs";

    private final MongoTemplate mongo;

    public MongoRunStore(MongoTemplate mongo) {
        this.mongo = mongo;
        // "latest runs" is the only query: keep it off a collection scan
        mongo.indexOps(COLLECTION).createIndex(new Index().on("startedAt", Sort.Direction.DESC));
    }

    @Override
    public MonitoringRun save(MonitoringRun run) {
        return mongo.save(run, COLLECTION);
    }

    @Override
    public List<MonitoringRun> latest(int limit) {
        Query query = new Query().with(Sort.by(Sort.Direction.DESC, "startedAt")).limit(limit);
        return mongo.find(query, MonitoringRun.class, COLLECTION);
    }

    @Override
    public long count() {
        return mongo.count(new Query(), COLLECTION);
    }

    @Override
    public String description() {
        return "MongoDB database " + mongo.getDb().getName();
    }

    @Override
    public void ping() {
        mongo.getDb().runCommand(new Document("ping", 1));
    }
}
