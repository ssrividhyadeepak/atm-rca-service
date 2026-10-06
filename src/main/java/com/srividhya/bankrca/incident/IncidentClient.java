package com.srividhya.bankrca.incident;

/**
 * The ticket system an approved incident is submitted to. Two implementations, chosen by
 * rca.incident.mode: a mock that only records what it would have created, and ServiceNow.
 */
public interface IncidentClient {

    /** @param number the ticket system's own incident number */
    record Created(String number, String system) {
    }

    /** For the startup summary. */
    String description();

    /**
     * Creates the incident. Submitting the same draft twice must not create two incidents:
     * the draft id is the key the ticket system is asked about first.
     *
     * @throws RuntimeException with a message safe to show, when it cannot be created
     */
    Created create(IncidentDraft draft);
}
