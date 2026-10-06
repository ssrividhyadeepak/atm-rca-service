package com.srividhya.bankrca.incident;

import org.springframework.data.annotation.Id;

/**
 * An incident on its way from an RCA finding to the ticket system.
 *
 * @param status DRAFT (waiting for a person), APPROVED (approved, not yet in the ticket system),
 *        SUBMITTED (in the ticket system, see incidentNumber) or REJECTED
 * @param note optional text from the assistant that drafted it, checked against the finding
 * @param createdBy who drafted it: a person's client, or the assistant's
 * @param decidedBy who approved or rejected it
 * @param incidentSystem where it was submitted: "mock" or the ServiceNow instance
 * @param lastError why the last submission attempt failed; null once it has succeeded
 */
public record IncidentDraft(
        @Id String id,
        String status,
        String reportId,
        String signatureId,
        int findingRank,
        String shortDescription,
        String description,
        String priority,
        String component,
        String assignmentGroup,
        String category,
        String suspectCommit,
        String runbook,
        String note,
        String createdBy,
        String createdAt,
        String decidedBy,
        String decidedAt,
        String decisionComment,
        String incidentNumber,
        String incidentSystem,
        String submittedAt,
        String lastError) {

    public IncidentDraft decided(String newStatus, String by, String at, String comment) {
        return new IncidentDraft(id, newStatus, reportId, signatureId, findingRank, shortDescription, description,
                priority, component, assignmentGroup, category, suspectCommit, runbook, note, createdBy, createdAt, by,
                at, comment, incidentNumber, incidentSystem, submittedAt, lastError);
    }

    public IncidentDraft submitted(String number, String system, String at) {
        return new IncidentDraft(id, "SUBMITTED", reportId, signatureId, findingRank, shortDescription, description,
                priority, component, assignmentGroup, category, suspectCommit, runbook, note, createdBy, createdAt,
                decidedBy, decidedAt, decisionComment, number, system, at, null);
    }

    public IncidentDraft failed(String error) {
        return new IncidentDraft(id, status, reportId, signatureId, findingRank, shortDescription, description, priority,
                component, assignmentGroup, category, suspectCommit, runbook, note, createdBy, createdAt, decidedBy,
                decidedAt, decisionComment, incidentNumber, incidentSystem, submittedAt, error);
    }
}
