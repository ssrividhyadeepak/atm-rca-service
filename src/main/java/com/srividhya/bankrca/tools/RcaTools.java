package com.srividhya.bankrca.tools;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.srividhya.bankrca.change.ChangeService;
import com.srividhya.bankrca.change.ChangeService.ChangeSearch;
import com.srividhya.bankrca.failure.FailedTransaction;
import com.srividhya.bankrca.failure.FailureRetrievalService;
import com.srividhya.bankrca.incident.IncidentService;
import com.srividhya.bankrca.investigation.Investigation;
import com.srividhya.bankrca.investigation.InvestigationService;
import com.srividhya.bankrca.investigation.InvestigationService.FixPlanInput;
import com.srividhya.bankrca.investigation.InvestigationService.HypothesisInput;
import com.srividhya.bankrca.incident.IncidentService.DraftResult;
import com.srividhya.bankrca.knowledge.KnowledgeHit;
import com.srividhya.bankrca.knowledge.KnowledgeService;
import com.srividhya.bankrca.rca.RcaReport;
import com.srividhya.bankrca.rca.RcaReport.Finding;
import com.srividhya.bankrca.rca.RcaService;
import com.srividhya.bankrca.security.Scopes;
import com.srividhya.bankrca.source.SuspectService;
import com.srividhya.bankrca.source.SuspectService.SuspectSearch;
import com.srividhya.bankrca.trace.TraceService;
import com.srividhya.bankrca.trace.TraceService.TraceNotFoundException;
import com.srividhya.bankrca.trace.TraceTimeline;

/**
 * The functions an assistant may call. Design rules:
 * - each does one thing, with named, described parameters and a typed result
 * - all are read-only except draftIncident, which creates a draft that a person must approve;
 *   there is deliberately no tool that approves or submits
 * - results are already masked
 * - arguments are validated; a refusal says what to send instead, so the model can correct itself
 * - results are capped in size
 * - each needs a scope, which the caller's token must carry
 * - every call goes through ToolAudit
 */
@Component
public class RcaTools {

    private static final int MAX_QUERY_CHARS = 500;
    private static final int MAX_HITS = 5;

    /** The latest RCA report in brief. */
    public record FailureSummary(String reportId, String from, String to, String generatedAt, String source,
            int totalEvents, String headline, List<FindingSummary> findings) {
    }

    /** One finding in a line; getFinding(rank) returns the whole of it. */
    public record FindingSummary(int rank, String severity, String status, String category, String component,
            String problem, int count, String timePattern, String suspectCommit, String runbook) {
    }

    /** @param total how many failures matched, of which signals are the newest */
    public record FailureSignals(String from, String to, int total, List<FailureSignal> signals) {
    }

    /** One failed transaction: enough to choose it and follow its trace id. */
    public record FailureSignal(String timestamp, String transaction, String component, String pod, String bankId,
            String traceId, String exception, String message) {
    }

    /**
     * @param verdict MATCH when at least one document really matches, NO_MATCH when the best is only a weak resemblance
     * @param hits best first; with NO_MATCH these are the nearest documents, for reference only
     */
    public record KnowledgeSearchResult(String verdict, String searchedBy, List<KnowledgeHit> hits) {
    }

    private final RcaService rca;
    private final KnowledgeService knowledge;
    private final ToolAudit audit;
    private final IncidentService incidents;
    private final FailureRetrievalService failures;
    private final TraceService traces;
    private final Clock clock;
    private final ChangeService changes;
    private final SuspectService suspects;
    private final InvestigationService investigations;

    public RcaTools(RcaService rca, KnowledgeService knowledge, ToolAudit audit, IncidentService incidents,
            FailureRetrievalService failures, TraceService traces, Clock clock, ChangeService changes,
            SuspectService suspects, InvestigationService investigations) {
        this.investigations = investigations;
        this.suspects = suspects;
        this.changes = changes;
        this.failures = failures;
        this.traces = traces;
        this.clock = clock;
        this.incidents = incidents;
        this.rca = rca;
        this.knowledge = knowledge;
        this.audit = audit;
    }

    @Tool(name = "getFailureSummary", description = """
            The latest root cause analysis in brief: the time window, how many failure events,
            a one-sentence headline, and every finding in one line each - rank, severity
            (HIGH, MEDIUM, LOW), whether it is NEW or RECURRING, category, component, the
            exception or problem, event count, time pattern, a suspect commit and the matching
            runbook when there are any. Start here. Use getFinding with a rank for the details.""")
    public FailureSummary getFailureSummary() {
        return audit.run("getFailureSummary", Set.of(Scopes.RCA_READ), Map.of(), () -> {
            RcaReport r = latest();
            List<FindingSummary> findings = r.findings().stream()
                    .map(f -> new FindingSummary(f.rank(), f.severity(), f.status(), f.category(), f.component(),
                            f.exception() == null ? f.title() : f.exception().substring(f.exception().lastIndexOf('.') + 1),
                            f.count(), f.timePattern(), f.suspectCommit(),
                            f.knowledge().stream().filter(k -> k.type().equals(KnowledgeService.RUNBOOK))
                                    .map(k -> k.id()).findFirst().orElse(null)))
                    .toList();
            return new FailureSummary(r.id(), r.from(), r.to(), r.generatedAt(), r.source(), r.totalEvents(),
                    r.headline(), findings);
        });
    }

    @Tool(name = "getFinding", description = """
            Everything about one finding of the latest analysis: the likely cause, the next
            step, the evidence (counts, times, pods, sample trace ids), where it failed in the
            source code with the line and the commits, and the runbook and past RCAs that match.
            Take the rank from getFailureSummary.""")
    public Finding getFinding(
            @ToolParam(description = "Rank of the finding in the latest analysis, starting at 1") Integer rank) {
        return audit.run("getFinding", Set.of(Scopes.RCA_READ), args("rank", rank), () -> {
            RcaReport r = latest();
            if (rank == null || rank < 1 || rank > r.findings().size()) {
                throw new IllegalArgumentException("'rank' must be between 1 and " + r.findings().size()
                        + ", the number of findings in report " + r.id());
            }
            return r.findings().get(rank - 1);
        });
    }

    @Tool(name = "lookupRunbook", description = """
            Find the runbook for a problem. Give the exception class name when you have it
            (that gives an exact match), or describe the problem in plain words. Returns the
            verdict MATCH or NO_MATCH, and for each runbook how it matched - EXACT (written for
            that exception) or SEMANTIC (reads alike, with a score from 0 to 1) - and its
            mitigation. With NO_MATCH say that there is no runbook; do not present the nearest one as the answer.""")
    public KnowledgeSearchResult lookupRunbook(
            @ToolParam(description = "Exception class name or a description of the problem, at most 500 characters") String query,
            @ToolParam(required = false, description = "How many runbooks to return, 1 to 5 (default 3)") Integer limit) {
        return audit.run("lookupRunbook", Set.of(Scopes.KB_READ), args("query", query, "limit", limit),
                () -> search(query, limit, KnowledgeService.RUNBOOK));
    }

    @Tool(name = "searchHistoricalRca", description = """
            Find past root cause analyses of similar failures: what the cause was and how it
            was resolved. Give the exception class name when you have it, or describe the
            failure in plain words. Returns the verdict MATCH or NO_MATCH, and for each past RCA
            its date, how it matched (EXACT or SEMANTIC with a score), the root cause and the
            resolution. With NO_MATCH say that nothing similar is on record.""")
    public KnowledgeSearchResult searchHistoricalRca(
            @ToolParam(description = "Exception class name or a description of the failure, at most 500 characters") String query,
            @ToolParam(required = false, description = "How many past RCAs to return, 1 to 5 (default 3)") Integer limit) {
        return audit.run("searchHistoricalRca", Set.of(Scopes.KB_READ), args("query", query, "limit", limit),
                () -> search(query, limit, KnowledgeService.PAST_RCA));
    }

    @Tool(name = "gitFindSuspects", description = """
            Commits that reached the deployed branch before a failure began, ranked as suspects
            by fixed rules: how close in time (up to 50), whether the commit changes the failing
            class (35), and whether it changes what runs - code, config or build files - rather
            than only tests or docs (15). Each suspect has its score, the reasons for it, the
            files changed, and the pull request or change number when the commit message names
            one. Give failingClass (the class of the first application frame, from getFinding)
            whenever there is a stack trace. The score ranks where to look first; it is not
            proof. Commit time is not deployment time: confirm with serviceNowRecentChanges.""")
    public SuspectSearch gitFindSuspects(
            @ToolParam(description = "Component (container) name as in the logs, e.g. withdrawal-p1") String component,
            @ToolParam(description = "When the failures started, ISO-8601 UTC, e.g. 2026-10-02T09:15:00Z") String failureTime,
            @ToolParam(required = false, description = "How many hours before the failure to look, 1 to 720 (default 168)") Integer hours,
            @ToolParam(required = false, description = "Fully qualified class of the failing stack frame, e.g. com.example.bank.withdrawal.host.HostAuthClient") String failingClass) {
        return audit.run("gitFindSuspects", Set.of(Scopes.CODE_READ),
                args("component", component, "failureTime", failureTime, "hours", hours, "failingClass", failingClass), () -> {
                    if (isBlank(component) || isBlank(failureTime)) {
                        throw new IllegalArgumentException("'component' and 'failureTime' are required");
                    }
                    if (hours != null && (hours < 1 || hours > 720)) {
                        throw new IllegalArgumentException("'hours' must be between 1 and 720");
                    }
                    Instant failure;
                    try {
                        failure = Instant.parse(failureTime.strip());
                    } catch (DateTimeParseException e) {
                        throw new IllegalArgumentException("'failureTime' must be a time such as 2026-10-02T09:15:00Z");
                    }
                    if (failure.isAfter(clock.instant().plusSeconds(300))) {
                        throw new IllegalArgumentException("'failureTime' is in the future");
                    }
                    return suspects.find(component.strip(), failure, Duration.ofHours(hours == null ? 168 : hours),
                            isBlank(failingClass) ? null : failingClass.strip());
                });
    }

    @Tool(name = "rcaCollectEvidence", description = """
            Start an investigation of one finding: gathers, in one call, the failure itself, one
            failed request traced across services with the point where it went wrong, the
            failing line in the code, the commits and the change requests before the failures
            began, and the matching runbook and past RCAs. Returns an investigation id and the
            evidence as numbered items (E1, E2 ...), each a sentence with its reference and the
            points it carries. Use this first when asked for the root cause of a finding, then
            reason over the evidence and record your conclusions with rcaRecordHypotheses. Use
            the single-purpose tools (splunkTraceRequest, gitFindSuspects ...) to dig further.""")
    public Investigation rcaCollectEvidence(
            @ToolParam(description = "Rank of the finding, from getFailureSummary") Integer rank) {
        return audit.run("rcaCollectEvidence", Set.of(Scopes.INVESTIGATION_WRITE, Scopes.RCA_READ, Scopes.LOGS_READ,
                Scopes.CODE_READ, Scopes.CHANGE_READ), args("rank", rank), () -> investigations.collect(rank));
    }

    @Tool(name = "rcaRecordHypotheses", description = """
            Record your ranked hypotheses for an investigation: 1 to 5, each a statement of the
            cause in your own words plus the ids of the evidence that supports it. Every id must
            be in the investigation, and a statement may name a change request, commit, runbook
            or past RCA only if the evidence it cites contains it; otherwise the call is refused
            and says what to correct. The score (0-95) and level of each hypothesis are computed
            by the service from the evidence cited, and the list comes back ranked by it: the
            score says how well supported a hypothesis is, not how likely it is to be right.
            Recording again replaces the previous set. Offer alternatives, not one answer.""")
    public Investigation rcaRecordHypotheses(
            @ToolParam(description = "Investigation id from rcaCollectEvidence, e.g. INV-1a2b3c4d") String investigationId,
            @ToolParam(description = "The hypotheses: statement (at most 600 characters), evidenceIds (e.g. [\"E3\", \"E5\"]) and, optionally, your own confidence LOW, MEDIUM or HIGH") List<HypothesisInput> hypotheses) {
        return audit.run("rcaRecordHypotheses", Set.of(Scopes.INVESTIGATION_WRITE),
                args("investigationId", investigationId, "hypotheses", hypotheses == null ? null : hypotheses.size()), () -> {
                    try {
                        return investigations.recordHypotheses(investigationId, hypotheses);
                    } catch (InvestigationService.NotFoundException e) {
                        throw new IllegalArgumentException(e.getMessage());
                    }
                });
    }

    @Tool(name = "rcaRecordFixPlan", description = """
            Record the fix plan for one hypothesis of an investigation: what to do, how to undo
            it, how to test it, and what it could affect. Optionally include edits - single-line
            changes to code or config. The service checks the plan: it may name a change, commit,
            runbook or past RCA only if the evidence contains it; blastRadiusComponents must be
            among the investigation's knownComponents; and each edit's oldCode must equal the
            deployed line, or the call is refused and shows what the line really is. The plan is
            stored as PROPOSED. Only a person can approve it, outside this conversation - say so
            when you report it. Recording again replaces the plan.""")
    public Investigation rcaRecordFixPlan(
            @ToolParam(description = "Investigation id, e.g. INV-1a2b3c4d") String investigationId,
            @ToolParam(description = "Rank of the hypothesis the plan is for, normally 1") Integer hypothesisRank,
            @ToolParam(description = "The plan") FixPlanInput plan) {
        return audit.run("rcaRecordFixPlan", Set.of(Scopes.INVESTIGATION_WRITE),
                args("investigationId", investigationId, "hypothesisRank", hypothesisRank, "edits",
                        plan == null || plan.edits() == null ? 0 : plan.edits().size()),
                () -> known(() -> investigations.recordFixPlan(investigationId, hypothesisRank, plan)));
    }

    @Tool(name = "investigationGet", description = """
            The current state of an investigation: its evidence (including what a developer set
            aside or added), the hypotheses with their scores, the fix plan and its status, the
            pull request if one was drafted, and the history of who did what. Call it when a
            conversation comes back to an investigation, before answering from memory.""")
    public Investigation investigationGet(
            @ToolParam(description = "Investigation id, e.g. INV-1a2b3c4d") String investigationId) {
        return audit.run("investigationGet", Set.of(Scopes.RCA_READ), args("investigationId", investigationId),
                () -> known(() -> investigations.get(investigationId)));
    }

    @Tool(name = "investigationExcludeEvidence", description = """
            Set a piece of evidence aside because the developer says it does not apply ("that
            change was never deployed", "that commit is unrelated"), or bring it back with
            exclude=false. Use it only on the developer's say-so, with their reason. Excluded
            evidence stays listed and counts for nothing: every hypothesis is scored again and
            the new ranking is returned. An approved fix plan goes back to PROPOSED.""")
    public Investigation investigationExcludeEvidence(
            @ToolParam(description = "Investigation id, e.g. INV-1a2b3c4d") String investigationId,
            @ToolParam(description = "Evidence id, e.g. E8") String evidenceId,
            @ToolParam(required = false, description = "The developer's reason; required when excluding") String reason,
            @ToolParam(required = false, description = "false to bring the evidence back (default true)") Boolean exclude) {
        return audit.run("investigationExcludeEvidence", Set.of(Scopes.INVESTIGATION_WRITE),
                args("investigationId", investigationId, "evidenceId", evidenceId, "exclude", exclude),
                () -> known(() -> investigations.excludeEvidence(investigationId, evidenceId, exclude == null || exclude, reason)));
    }

    @Tool(name = "investigationAddNote", description = """
            Add something the developer knows that no tool returned ("the host team confirms the
            gateway was healthy"). It becomes a NOTE in the evidence that hypotheses can cite. It
            adds nothing to a score, because the service cannot verify it. Record the
            developer's words, not your own conclusions.""")
    public Investigation investigationAddNote(
            @ToolParam(description = "Investigation id, e.g. INV-1a2b3c4d") String investigationId,
            @ToolParam(description = "What the developer said, at most 1000 characters") String note) {
        return audit.run("investigationAddNote", Set.of(Scopes.INVESTIGATION_WRITE),
                args("investigationId", investigationId, "noteChars", note == null ? null : note.length()),
                () -> known(() -> investigations.addNote(investigationId, note)));
    }

    @Tool(name = "gitDraftPullRequest", description = """
            Open a DRAFT pull request for an investigation's fix plan: a new branch carrying the
            plan's edits, with the cause, evidence, test plan, blast radius and rollback in the
            description. It works only after a person has approved the plan; until then the
            call is refused, and you cannot approve it yourself. A draft cannot be merged until
            a person reviews it and marks it ready. Calling again returns the same pull request.""")
    public Investigation gitDraftPullRequest(
            @ToolParam(description = "Investigation id, e.g. INV-1a2b3c4d") String investigationId) {
        return audit.run("gitDraftPullRequest", Set.of(Scopes.PR_WRITE), args("investigationId", investigationId),
                () -> known(() -> investigations.draftPullRequest(investigationId)));
    }

    /** An unknown investigation is a refusal the model can act on. */
    private static <T> T known(java.util.function.Supplier<T> body) {
        try {
            return body.get();
        } catch (InvestigationService.NotFoundException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    @Tool(name = "serviceNowRecentChanges", description = """
            Change requests made to one component: number, type, state, risk, what was changed,
            who owns it and when the work was done. Use it to answer "what changed before this
            started failing". Give failureTime (the first failure's timestamp, from a finding,
            splunkFindFailures or a trace) and each change comes back with its timing against
            it - BEFORE_FAILURE with the minutes between, DURING_FAILURE_START or AFTER_FAILURE -
            closest before the failure first. The timing is arithmetic, not a verdict: a change
            shortly before a failure is a suspect, and one that started after it is not the cause.
            Read-only. An empty list means no change is on record for that component and window.""")
    public ChangeSearch serviceNowRecentChanges(
            @ToolParam(description = "Component (container) name as in the logs, e.g. withdrawal-p1") String component,
            @ToolParam(required = false, description = "How many hours back from now to look, 1 to 336 (default 72)") Integer hours,
            @ToolParam(required = false, description = "When the failures started, ISO-8601 UTC, e.g. 2026-10-02T09:15:00Z") String failureTime) {
        return audit.run("serviceNowRecentChanges", Set.of(Scopes.CHANGE_READ),
                args("component", component, "hours", hours, "failureTime", failureTime), () -> {
                    if (isBlank(component)) {
                        throw new IllegalArgumentException("'component' is required, e.g. withdrawal-p1");
                    }
                    if (hours != null && (hours < 1 || hours > 336)) {
                        throw new IllegalArgumentException("'hours' must be between 1 and 336");
                    }
                    Instant failure;
                    try {
                        failure = isBlank(failureTime) ? null : Instant.parse(failureTime.strip());
                    } catch (DateTimeParseException e) {
                        throw new IllegalArgumentException("'failureTime' must be a time such as 2026-10-02T09:15:00Z");
                    }
                    Instant to = clock.instant();
                    return changes.search(List.of(component.strip()), to.minus(Duration.ofHours(hours == null ? 72 : hours)),
                            to, failure);
                });
    }

    @Tool(name = "splunkFindFailures", description = """
            Failed transactions from the logs, newest first: for each, when it happened, the
            transaction type, the component and pod, the bank id, the exception and message
            (masked), and its trace id. This is the starting point for investigating one
            failure: pick a trace id and follow it with splunkTraceRequest. Narrow the list
            by transaction type, component, bank id or exception class. The window is the last
            1 to 24 hours; at most 50 are returned, and total says how many matched.""")
    public FailureSignals splunkFindFailures(
            @ToolParam(required = false, description = "How many hours back to look, 1 to 24 (default 24)") Integer hours,
            @ToolParam(required = false, description = "Transaction type, e.g. cash-withdrawal, cash-deposit, balance-inquiry") String transaction,
            @ToolParam(required = false, description = "Component (container) name, e.g. withdrawal-p1") String component,
            @ToolParam(required = false, description = "Bank id, e.g. Q1231") String bankId,
            @ToolParam(required = false, description = "Exception class name, simple or fully qualified, e.g. NullPointerException") String exception,
            @ToolParam(required = false, description = "How many to return, 1 to 50 (default 10)") Integer limit) {
        return audit.run("splunkFindFailures", Set.of(Scopes.LOGS_READ), args("hours", hours, "transaction", transaction,
                "component", component, "bankId", bankId, "exception", exception, "limit", limit), () -> {
                    if (hours != null && (hours < 1 || hours > 24)) {
                        throw new IllegalArgumentException("'hours' must be between 1 and 24");
                    }
                    if (limit != null && (limit < 1 || limit > 50)) {
                        throw new IllegalArgumentException("'limit' must be between 1 and 50");
                    }
                    for (String filter : new String[] { transaction, component, bankId, exception }) {
                        if (filter != null && filter.length() > 200) {
                            throw new IllegalArgumentException("'transaction', 'component', 'bankId' and 'exception' take at most 200 characters");
                        }
                    }
                    Instant to = clock.instant();
                    Instant from = to.minus(Duration.ofHours(hours == null ? 24 : hours));
                    List<FailedTransaction> matched = new ArrayList<>(failures.retrieve(from, to).items().stream()
                            // A failure can only be followed if it carries a trace id
                            .filter(f -> f.traceId() != null)
                            .filter(f -> matches(transaction, f.transaction()) && matches(component, f.component())
                                    && matches(bankId, f.bankId())
                                    && (isBlank(exception) || (f.exception() != null && (f.exception().equals(exception.strip())
                                            || f.exception().endsWith("." + exception.strip())))))
                            .toList());
                    Collections.reverse(matched);
                    List<FailureSignal> signals = matched.stream().limit(limit == null ? 10 : limit)
                            .map(f -> new FailureSignal(f.timestamp(), f.transaction(), f.component(), f.pod(), f.bankId(),
                                    f.traceId(), f.exception(), f.message()))
                            .toList();
                    return new FailureSignals(from.toString(), to.toString(), matched.size(), signals);
                });
    }

    @Tool(name = "splunkTraceRequest", description = """
            Follow one request across every component that logged it, by trace id. Returns the
            steps in time order - each request received, each call to a dependency and its
            answer, the exception, each response sent - with component, status, latency and the
            logged body (sensitive fields masked; field names and nulls are kept). calls pairs
            each call to a dependency with its answer. divergence says where the request left
            the normal path, worked out by fixed rules: TIMEOUT, DOWNSTREAM_ERROR, DECLINED,
            NULL_FIELD, MISSING_FIELD or EXCEPTION, with the step that shows it. Use it to see
            what one failed transaction actually did; the bodies are evidence, quote them exactly.""")
    public TraceTimeline splunkTraceRequest(
            @ToolParam(description = "Trace id of the request, e.g. 80cc944e-7925-7d33-3799-2694c2a6898a, from splunkFindFailures or a finding's sampleTraceIds") String traceId) {
        return audit.run("splunkTraceRequest", Set.of(Scopes.LOGS_READ), args("traceId", traceId), () -> {
            Instant to = clock.instant();
            try {
                return traces.trace(traceId, to.minus(Duration.ofHours(24)), to);
            } catch (TraceNotFoundException e) {
                // A refusal the model can act on, not a failure of the tool
                throw new IllegalArgumentException("'traceId' matched nothing: " + e.getMessage()
                        + ". Check the id, or take a recent one from splunkFindFailures");
            }
        });
    }

    private static boolean matches(String wanted, String actual) {
        return isBlank(wanted) || wanted.strip().equalsIgnoreCase(actual);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Tool(name = "draftIncident", description = """
            Draft an incident for one finding of the latest analysis. This only creates a
            draft: nothing is sent to the ticket system until a person approves it, and
            approval cannot be given through a tool. The draft's text, priority and assignment
            group are built from the finding. If an incident for the same problem already
            exists it is returned instead (created=false). A finding that only repeats another
            component's failure is refused; draft its origin. Tell the user the draft id and
            that it is waiting for approval.""")
    public DraftResult draftIncident(
            @ToolParam(description = "Rank of the finding in the latest analysis, starting at 1") Integer rank,
            @ToolParam(required = false, description = "Optional note for the reviewer, at most 600 characters. It may only mention ids, files, commits and exceptions that getFinding returned for this finding") String note) {
        return audit.run("draftIncident", Set.of(Scopes.RCA_READ, Scopes.INCIDENT_WRITE),
                args("rank", rank, "noteChars", note == null ? null : note.length()), () -> incidents.draft(rank, note));
    }

    private KnowledgeSearchResult search(String query, Integer limit, String type) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("'query' is required: an exception class name or a description of the problem");
        }
        if (query.length() > MAX_QUERY_CHARS) {
            throw new IllegalArgumentException("'query' is too long: at most " + MAX_QUERY_CHARS + " characters");
        }
        if (limit != null && (limit < 1 || limit > MAX_HITS)) {
            throw new IllegalArgumentException("'limit' must be between 1 and " + MAX_HITS);
        }
        // History means before the day being analysed: today's own report is not "the past"
        String today = type.equals(KnowledgeService.PAST_RCA) ? rca.latest().map(RcaReport::id).orElse(null) : null;
        List<KnowledgeHit> hits = knowledge.search(query.strip(), type, limit == null ? 3 : limit, today);
        // The verdict is decided here, not left to the model to read out of the scores
        boolean match = hits.stream().anyMatch(KnowledgeHit::related);
        return new KnowledgeSearchResult(match ? "MATCH" : "NO_MATCH", knowledge.mode(), hits);
    }

    private RcaReport latest() {
        return rca.latest().orElseThrow(() -> new IllegalArgumentException(
                "There is no RCA report yet. One is written by each monitoring run."));
    }

    /** Like Map.of but allows null values (optional parameters). */
    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
