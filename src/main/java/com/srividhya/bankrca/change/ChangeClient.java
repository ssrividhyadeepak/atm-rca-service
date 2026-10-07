package com.srividhya.bankrca.change;

import java.time.Instant;
import java.util.List;

/**
 * Where change requests are read from. Two implementations, chosen by rca.change.mode: a
 * stub that reads a JSON file, and ServiceNow's change_request table. Read-only either way.
 */
public interface ChangeClient {

    /** For the startup summary. */
    String description();

    /**
     * Changes to any of the components whose work overlaps the window, newest first.
     *
     * @param components component names as they appear in the logs; never empty
     * @throws RuntimeException with a message safe to show, when they cannot be read
     */
    List<ChangeRequest> changes(List<String> components, Instant from, Instant to, int limit);
}
