package com.srividhya.bankrca.storage;

import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.bson.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import com.srividhya.bankrca.rca.RcaInput;
import com.srividhya.bankrca.rca.RcaReport;

/**
 * Enterprise storage: rca_inputs and rca_reports hold one document per day, signature_history one per
 * signature with the earliest and latest time it was seen.
 */
@Component
@ConditionalOnProperty(name = "rca.storage", havingValue = "mongo")
public class MongoRcaStore implements RcaStore {

    static final String REPORTS = "rca_reports";
    static final String INPUTS = "rca_inputs";
    static final String HISTORY = "signature_history";

    private final MongoTemplate mongo;

    public MongoRcaStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void saveInput(RcaInput input) {
        mongo.save(input, INPUTS);
    }

    @Override
    public Optional<RcaInput> latestInput() {
        return mongo.find(new Query().with(Sort.by(Sort.Direction.DESC, "_id")).limit(1), RcaInput.class, INPUTS)
                .stream().findFirst();
    }

    @Override
    public void saveReport(RcaReport report) {
        mongo.save(report, REPORTS);
    }

    @Override
    public Optional<RcaReport> latestReport() {
        return reports(1).stream().findFirst();
    }

    @Override
    public List<RcaReport> reports(int limit) {
        // The id is the day, so sorting by it is sorting by date
        return mongo.find(new Query().with(Sort.by(Sort.Direction.DESC, "_id")).limit(limit), RcaReport.class, REPORTS);
    }

    @Override
    public Map<String, Instant> firstSeen(Collection<String> signatureIds) {
        Map<String, Instant> known = new HashMap<>();
        for (Document d : mongo.find(new Query(Criteria.where("_id").in(signatureIds)), Document.class, HISTORY)) {
            known.put(d.getString("_id"), d.getDate("firstSeen").toInstant());
        }
        return known;
    }

    @Override
    public void recordSeen(Map<String, Instant[]> seen) {
        // $min / $max let two runs record the same signature in any order without losing the earliest time
        seen.forEach((id, times) -> mongo.upsert(new Query(Criteria.where("_id").is(id)),
                new Update().min("firstSeen", Date.from(times[0])).max("lastSeen", Date.from(times[1])), HISTORY));
    }
}
