package com.srividhya.bankrca.incident;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import com.srividhya.bankrca.assistant.AnswerGuard;
import com.srividhya.bankrca.incident.IncidentClient.Created;
import com.srividhya.bankrca.knowledge.KnowledgeRef;
import com.srividhya.bankrca.knowledge.KnowledgeService;
import com.srividhya.bankrca.rca.RcaReport;
import com.srividhya.bankrca.rca.RcaReport.Finding;
import com.srividhya.bankrca.rca.RcaService;
import com.srividhya.bankrca.security.Caller;
import com.srividhya.bankrca.security.PiiMasker;
import com.srividhya.bankrca.security.SecurityProps;
import com.srividhya.bankrca.security.TraceIdFilter;

import tools.jackson.databind.json.JsonMapper;

/**
 * From an RCA finding to an incident, with a person in between:
 *
 * <pre>
 * finding --draft--> DRAFT --approve--> APPROVED --submit--> SUBMITTED (incident number)
 *                      \--reject--> REJECTED
 * </pre>
 *
 * A draft can be made by anyone with the scope, including the assistant. Only a person can
 * approve one - approval is not offered as a tool - and with security on it must be someone
 * other than whoever drafted it. Nothing reaches the ticket system before approval. Every
 * step is written to the audit log.
 */
@Service
public class IncidentService {

    /** @param created false when an incident for the same problem already existed and is returned instead */
    public record DraftResult(boolean created, String message, IncidentDraft draft) {
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);
    private static final Logger audit = LoggerFactory.getLogger("AUDIT");
    private static final int MAX_NOTE_CHARS = 600;
    private static final int MAX_COMMENT_CHARS = 500;

    private final RcaService rca;
    private final KnowledgeService knowledge;
    private final IncidentStore store;
    private final IncidentClient client;
    private final IncidentProps props;
    private final SecurityProps security;
    private final Caller caller;
    private final PiiMasker masker;
    private final Clock clock;
    private final JsonMapper json = new JsonMapper();

    public IncidentService(RcaService rca, KnowledgeService knowledge, IncidentStore store, IncidentClient client,
            IncidentProps props, SecurityProps security, Caller caller, PiiMasker masker, Clock clock) {
        this.rca = rca;
        this.knowledge = knowledge;
        this.store = store;
        this.client = client;
        this.props = props;
        this.security = security;
        this.caller = caller;
        this.masker = masker;
        this.clock = clock;
    }

    public String description() {
        return client.description();
    }

    /**
     * @param rank the finding of the latest report to raise
     * @param note optional text to add, e.g. from the assistant; refused if it cites anything the finding does not contain
     * @throws IllegalArgumentException for a rank that does not exist, a propagated finding, or an ungrounded note
     */
    public synchronized DraftResult draft(Integer rank, String note) {
        RcaReport report = rca.latest().orElseThrow(() -> new IllegalArgumentException(
                "There is no RCA report yet. One is written by each monitoring run."));
        if (rank == null || rank < 1 || rank > report.findings().size()) {
            throw new IllegalArgumentException("'rank' must be between 1 and " + report.findings().size()
                    + ", the number of findings in report " + report.id());
        }
        Finding f = report.findings().get(rank - 1);
        if (f.category().equals("PROPAGATED")) {
            String origin = report.findings().stream().filter(o -> f.relatedSignatureIds().contains(o.signatureId()))
                    .map(o -> "finding " + o.rank() + " (" + o.title() + ")").findFirst().orElse("its origin");
            throw new IllegalArgumentException("'rank' " + rank + " only repeats a failure that started elsewhere. "
                    + "Raise the incident for " + origin + " instead");
        }

        // One incident per problem: a retry, a second run or a second client gets the first one back
        Instant cutoff = clock.instant().minus(props.dedupeWindow());
        for (IncidentDraft existing : store.activeForSignature(f.signatureId())) {
            if (Instant.parse(existing.createdAt()).isAfter(cutoff)) {
                return new DraftResult(false, "An incident for this problem already exists (" + existing.status()
                        + (existing.incidentNumber() == null ? "" : ", " + existing.incidentNumber()) + "); returned as it is",
                        existing);
            }
        }

        String cleanNote = null;
        if (note != null && !note.isBlank()) {
            if (note.length() > MAX_NOTE_CHARS) {
                throw new IllegalArgumentException("'note' is too long: at most " + MAX_NOTE_CHARS + " characters");
            }
            List<String> invented = AnswerGuard.ungrounded(note, "", List.of(json.writeValueAsString(f)));
            if (!invented.isEmpty()) {
                throw new IllegalArgumentException("'note' cites " + invented + ", which the finding does not contain. "
                        + "Use only what getFinding returned, or leave the note out");
            }
            cleanNote = masker.mask(note.strip());
        }

        String runbook = f.knowledge().stream().filter(k -> k.type().equals(KnowledgeService.RUNBOOK))
                .map(KnowledgeRef::id).findFirst().orElse(null);
        String owner = runbook == null ? null : knowledge.owner(runbook);
        IncidentDraft draft = store.save(new IncidentDraft("DRAFT-" + UUID.randomUUID().toString().substring(0, 8), "DRAFT",
                report.id(), f.signatureId(), f.rank(), shortDescription(f), description(report, f, cleanNote),
                props.priority().getOrDefault(f.severity(), "3 - Moderate"), f.component(),
                owner == null || owner.isBlank() ? props.defaultAssignmentGroup() : owner, f.category(),
                f.suspectCommit(), runbook, cleanNote, caller.client(), now(), null, null, null, null, null, null, null));
        record("incident.draft", draft, "OK", null);
        return new DraftResult(true, "Draft created. It needs a person's approval before anything is sent: "
                + "POST /api/incidents/" + draft.id() + "/approve", draft);
    }

    public IncidentDraft get(String id) {
        return store.find(id).orElseThrow(() -> new NotFoundException("There is no incident draft '" + id + "'"));
    }

    public List<IncidentDraft> latest(int limit) {
        return store.latest(limit);
    }

    /** Approves the draft and submits it to the ticket system. */
    public synchronized IncidentDraft approve(String id, String comment) {
        IncidentDraft draft = get(id);
        requireStatus(draft, "DRAFT", "approved");
        String approver = caller.client();
        if (props.fourEyes() && !security.off() && approver.equals(draft.createdBy())) {
            record("incident.approve", draft, "DENIED", "approver is the client that drafted it");
            throw new Caller.DeniedException("The draft was created by '" + approver + "' and must be approved by "
                    + "someone else");
        }
        IncidentDraft approved = store.save(draft.decided("APPROVED", approver, now(), comment(comment)));
        record("incident.approve", approved, "OK", null);
        return submit(approved);
    }

    public synchronized IncidentDraft reject(String id, String reason) {
        IncidentDraft draft = get(id);
        requireStatus(draft, "DRAFT", "rejected");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("'reason' is required when rejecting a draft");
        }
        IncidentDraft rejected = store.save(draft.decided("REJECTED", caller.client(), now(), comment(reason)));
        record("incident.reject", rejected, "OK", null);
        return rejected;
    }

    /** Tries again to submit a draft that was approved but could not be sent. */
    public synchronized IncidentDraft retrySubmit(String id) {
        IncidentDraft draft = get(id);
        requireStatus(draft, "APPROVED", "submitted");
        return submit(draft);
    }

    /** A failure leaves the draft APPROVED with the reason, to be retried; the approval is not lost. */
    private IncidentDraft submit(IncidentDraft approved) {
        try {
            Created created = client.create(approved);
            IncidentDraft submitted = store.save(approved.submitted(created.number(), created.system(), now()));
            record("incident.submit", submitted, "OK", null);
            log.info("Incident {} created in {} from draft {}", created.number(), created.system(), approved.id());
            return submitted;
        } catch (RuntimeException e) {
            log.warn("Draft {} could not be submitted: {}", approved.id(), e.getMessage());
            IncidentDraft failed = store.save(approved.failed(masker.mask(e.getMessage())));
            record("incident.submit", failed, "ERROR", e.getMessage());
            return failed;
        }
    }

    private static void requireStatus(IncidentDraft draft, String needed, String action) {
        if (!draft.status().equals(needed)) {
            throw new IllegalStateException("Draft " + draft.id() + " is " + draft.status()
                    + (draft.incidentNumber() == null ? "" : " (" + draft.incidentNumber() + ")")
                    + "; only a draft that is " + needed + " can be " + action);
        }
    }

    private String comment(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (text.length() > MAX_COMMENT_CHARS) {
            throw new IllegalArgumentException("The comment is too long: at most " + MAX_COMMENT_CHARS + " characters");
        }
        return masker.mask(text.strip());
    }

    private static String shortDescription(Finding f) {
        String text = "[RCA] " + f.title() + ": " + f.count() + " failures (" + f.timePattern() + ")";
        return text.length() <= 160 ? text : text.substring(0, 157) + "...";
    }

    /** Built from the finding alone: every line is something the analysis recorded. */
    private static String description(RcaReport report, Finding f, String note) {
        StringBuilder sb = new StringBuilder();
        sb.append("Raised from the automated root cause analysis of ").append(report.from()).append(" to ")
                .append(report.to()).append(" (report ").append(report.id()).append(", finding ").append(f.rank())
                .append(", signature ").append(f.signatureId()).append("). Reviewed and approved by a person before it was sent.\n\n");
        sb.append("What: ").append(f.title()).append(". ").append(f.severity()).append(", ").append(f.status())
                .append(", ").append(f.category()).append(".\n");
        sb.append("Likely cause (confidence ").append(f.confidence()).append("): ").append(f.likelyCause()).append('\n');
        sb.append("Next step: ").append(f.suggestedAction()).append("\n\nEvidence:\n");
        f.evidence().forEach(e -> sb.append("- ").append(e).append('\n'));
        if (note != null) {
            sb.append("\nNote added when drafting (AI-assisted): ").append(note).append('\n');
        }
        return sb.toString();
    }

    private String now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private void record(String action, IncidentDraft draft, String outcome, String error) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", Instant.now().toString());
        entry.put("traceId", MDC.get(TraceIdFilter.MDC_KEY));
        entry.put("client", caller.client());
        entry.put("action", action);
        entry.put("draft", draft.id());
        entry.put("signature", draft.signatureId());
        entry.put("status", draft.status());
        if (draft.incidentNumber() != null) {
            entry.put("incident", draft.incidentNumber());
        }
        entry.put("outcome", outcome);
        if (error != null) {
            entry.put("error", masker.mask(error));
        }
        audit.info(json.writeValueAsString(entry));
    }
}
