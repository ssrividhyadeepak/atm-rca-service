package com.srividhya.bankrca.incident;

import java.util.List;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/** Enterprise storage: one document per draft in incident_drafts. */
@Component
@ConditionalOnProperty(name = "rca.storage", havingValue = "mongo")
public class MongoIncidentStore implements IncidentStore {

    static final String COLLECTION = "incident_drafts";

    private final MongoTemplate mongo;

    public MongoIncidentStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public IncidentDraft save(IncidentDraft draft) {
        return mongo.save(draft, COLLECTION);
    }

    @Override
    public Optional<IncidentDraft> find(String id) {
        return Optional.ofNullable(mongo.findById(id, IncidentDraft.class, COLLECTION));
    }

    @Override
    public List<IncidentDraft> latest(int limit) {
        return mongo.find(new Query().with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(limit), IncidentDraft.class,
                COLLECTION);
    }

    @Override
    public List<IncidentDraft> activeForSignature(String signatureId) {
        return mongo.find(new Query(Criteria.where("signatureId").is(signatureId).and("status").ne("REJECTED"))
                .with(Sort.by(Sort.Direction.DESC, "createdAt")), IncidentDraft.class, COLLECTION);
    }
}
