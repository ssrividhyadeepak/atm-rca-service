package com.srividhya.bankrca.storage;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Shows under "storage" in /actuator/health: which store is in use and whether it answers. */
@Component("storage")
public class StorageHealthIndicator implements HealthIndicator {

    private final RunStore store;

    public StorageHealthIndicator(RunStore store) {
        this.store = store;
    }

    @Override
    public Health health() {
        try {
            store.ping();
            return Health.up().withDetail("store", store.description()).withDetail("runs", store.count()).build();
        } catch (RuntimeException e) {
            // The exception type only: driver messages can carry host details
            return Health.down().withDetail("store", store.description())
                    .withDetail("error", e.getClass().getSimpleName()).build();
        }
    }
}
