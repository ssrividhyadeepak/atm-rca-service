package com.srividhya.atmrca.storage;

import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.srividhya.atmrca.config.RcaProperties;

/**
 * Creates the MongoDB connection only when rca.storage=mongo, so a local run never tries to
 * reach a database. The connection is checked at startup: a wrong URI or an unreachable
 * cluster stops the service with a clear message instead of failing on the first request.
 */
@Configuration
@ConditionalOnProperty(name = "rca.storage", havingValue = "mongo")
public class MongoStorageConfig {

    private static final int CONNECT_SECONDS = 10;

    @Bean(destroyMethod = "close")
    MongoClient mongoClient(RcaProperties props) {
        String uri = props.mongo().uri();
        if (uri == null || uri.isBlank()) {
            throw new IllegalStateException("rca.storage is mongo but MONGODB_URI is not set. "
                    + "Set it to the connection string of your MongoDB");
        }
        ConnectionString connection;
        try {
            connection = new ConnectionString(uri.strip());
        } catch (IllegalArgumentException e) {
            // The driver's message can quote the URI, credentials included: do not pass it on
            throw new IllegalStateException("MONGODB_URI is not a valid MongoDB connection string. "
                    + "Expected mongodb://... or mongodb+srv://...");
        }
        MongoClient client = MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(connection)
                .applyToClusterSettings(c -> c.serverSelectionTimeout(CONNECT_SECONDS, TimeUnit.SECONDS))
                .build());
        try {
            client.getDatabase(props.mongo().database()).runCommand(new Document("ping", 1));
        } catch (RuntimeException e) {
            client.close();
            throw new IllegalStateException("Could not reach MongoDB at " + connection.getHosts() + " within "
                    + CONNECT_SECONDS + "s (" + e.getClass().getSimpleName() + "). Check MONGODB_URI, the network "
                    + "path and that this machine is allowed to connect");
        }
        return client;
    }

    @Bean
    MongoTemplate mongoTemplate(MongoClient client, RcaProperties props) {
        return new MongoTemplate(client, props.mongo().database());
    }
}
