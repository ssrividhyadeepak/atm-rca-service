package com.srividhya.bankrca.change;

/**
 * One change request from the change-management system, reduced to what matters when
 * asking "what changed before this started failing".
 *
 * @param component the monitored component the change was made to, as it is named in the logs
 * @param service the configuration item as the change system names it
 * @param type e.g. Normal, Standard, Emergency
 * @param startedAt endedAt when the work was done (the actual times when recorded, otherwise the planned ones); ISO-8601, UTC
 */
public record ChangeRequest(String number, String component, String service, String type, String state, String risk,
        String shortDescription, String description, String assignmentGroup, String startedAt, String endedAt,
        String closeCode) {
}
