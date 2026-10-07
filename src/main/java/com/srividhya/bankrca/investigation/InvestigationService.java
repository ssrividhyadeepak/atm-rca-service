package com.srividhya.bankrca.investigation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import com.srividhya.bankrca.change.ChangeService;
import com.srividhya.bankrca.change.ChangeService.TimedChange;
import com.srividhya.bankrca.investigation.Investigation.BlastRadius;
import com.srividhya.bankrca.investigation.Investigation.Edit;
import com.srividhya.bankrca.investigation.Investigation.Event;
import com.srividhya.bankrca.investigation.Investigation.Evidence;
import com.srividhya.bankrca.investigation.Investigation.FixPlan;
import com.srividhya.bankrca.investigation.Investigation.Hypothesis;
import com.srividhya.bankrca.investigation.Investigation.PullRequest;
import com.srividhya.bankrca.knowledge.KnowledgeRef;
import com.srividhya.bankrca.knowledge.KnowledgeService;
import com.srividhya.bankrca.rca.RcaReport;
import com.srividhya.bankrca.rca.RcaReport.Finding;
import com.srividhya.bankrca.rca.RcaService;
import com.srividhya.bankrca.security.Caller;
import com.srividhya.bankrca.pullrequest.PullRequestClient;
import com.srividhya.bankrca.security.PiiMasker;
import com.srividhya.bankrca.security.SecurityProps;
import com.srividhya.bankrca.source.SourceLocation;
import com.srividhya.bankrca.source.SourceRepository;
import com.srividhya.bankrca.source.SuspectService;
import com.srividhya.bankrca.source.SuspectService.Suspect;
import com.srividhya.bankrca.trace.TraceService;
import com.srividhya.bankrca.trace.TraceTimeline;

/**
 * Turns a finding into an investigation. The division of work is deliberate:
 *
 * - Code gathers the evidence - the failure, one request traced end to end, the changes and
 *   commits before it, the runbook and past RCAs - and gives each item an id and a weight.
 * - A model reads it and writes hypotheses, each citing evidence ids.
 * - Code checks every citation, refuses a hypothesis that names something the evidence does
 *   not contain, and scores what is left from the weights of the evidence cited.
 *
 * So the wording is the model's, and the facts and the score are not.
 */
@Service
public class InvestigationService {

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /** What a model sends for one hypothesis. */
    public record HypothesisInput(
            @ToolParam(description = "The cause, in a sentence or two; at most 600 characters") String statement,
            @ToolParam(description = "Ids of the evidence that supports it, e.g. [\"E3\", \"E8\"]") List<String> evidenceIds,
            @ToolParam(required = false, description = "Your own confidence: LOW, MEDIUM or HIGH") String confidence) {
    }

    /** What a model sends for a fix plan. */
    public record FixPlanInput(
            @ToolParam(description = "The fix in one sentence; becomes the pull request title") String summary,
            @ToolParam(description = "What to do, in order; 1 to 10 steps") List<String> steps,
            @ToolParam(description = "How to undo it if it makes things worse") String rollback,
            @ToolParam(description = "How to verify the fix before and after release; 1 to 10 checks") List<String> testPlan,
            @ToolParam(description = "Components the fix touches or could affect; each must be one of the investigation's knownComponents") List<String> blastRadiusComponents,
            @ToolParam(description = "What could be affected, and how badly if the fix is wrong") String blastRadius,
            @ToolParam(required = false, description = "Single-line code or config changes; each is checked against the deployed code") List<EditInput> edits) {
    }

    public record EditInput(
            @ToolParam(description = "File path in the repository, as shown in the evidence") String path,
            @ToolParam(description = "Line number, starting at 1") Integer line,
            @ToolParam(description = "The line as it is now, exactly") String oldCode,
            @ToolParam(description = "The line as it should be; one line") String newCode) {
    }

    private static final Logger log = LoggerFactory.getLogger(InvestigationService.class);
    public static final int MAX_HYPOTHESES = 5;
    private static final int MAX_STATEMENT = 600;
    private static final int MAX_TEXT = 1000;
    private static final int MAX_ITEMS = 10;
    private static final Duration LOOKBACK = Duration.ofDays(7);
    // Things a statement may only name if the evidence it cites contains them
    private static final Pattern NAMED = Pattern.compile("\\b(CHG\\d{4,}|INC\\d{4,}|RB-\\d+|RCA-[\\w-]+)\\b");
    private static final Pattern HASH = Pattern.compile("\\b(?=[0-9a-f]*\\d)(?=[0-9a-f]*[a-f])[0-9a-f]{7,40}\\b");

    private final RcaService rca;
    private final TraceService traces;
    private final ChangeService changes;
    private final SuspectService suspects;
    private final InvestigationStore store;
    private final PiiMasker masker;
    private final Caller caller;
    private final Clock clock;
    private final SourceRepository source;
    private final PullRequestClient pullRequests;
    private final SecurityProps security;

    public InvestigationService(RcaService rca, TraceService traces, ChangeService changes, SuspectService suspects,
            InvestigationStore store, PiiMasker masker, Caller caller, Clock clock, SourceRepository source,
            PullRequestClient pullRequests, SecurityProps security) {
        this.source = source;
        this.pullRequests = pullRequests;
        this.security = security;
        this.rca = rca;
        this.traces = traces;
        this.changes = changes;
        this.suspects = suspects;
        this.store = store;
        this.masker = masker;
        this.caller = caller;
        this.clock = clock;
    }

    public String pullRequestDescription() {
        return pullRequests.description();
    }

    public Investigation get(String id) {
        return store.find(id == null ? "" : id.strip()).orElseThrow(() -> new NotFoundException(
                "'investigationId' " + id + " does not exist: start one with rcaCollectEvidence"));
    }

    public List<Investigation> latest(int limit) {
        return store.latest(limit);
    }

    /**
     * Gathers the evidence for one finding of the latest report. A source that cannot be read
     * (no trace id, the change system down) is left out and logged; the rest is still returned.
     *
     * @throws IllegalArgumentException when there is no report or no such finding
     */
    public Investigation collect(Integer rank) {
        RcaReport report = rca.latest().orElseThrow(() -> new IllegalArgumentException(
                "'rank' cannot be used yet: there is no RCA report. One is written by each monitoring run."));
        if (rank == null || rank < 1 || rank > report.findings().size()) {
            throw new IllegalArgumentException("'rank' must be between 1 and " + report.findings().size());
        }
        Finding f = report.findings().get(rank - 1);
        Instant failure = time(f.firstSeen());
        List<Evidence> evidence = new ArrayList<>();
        Set<String> known = new LinkedHashSet<>(List.of(f.component()));

        add(evidence, "FINDING", f.signatureId(), f.firstSeen(), f.count() + " " + shortName(f.exception()) + " in "
                + f.component() + " (" + f.transaction() + "), first at " + f.firstSeen() + ", last at " + f.lastSeen()
                + ", pattern " + f.timePattern() + ". The rules say: " + f.likelyCause(), 10,
                "the failure itself");

        if (f.sampleTraceIds() != null && !f.sampleTraceIds().isEmpty()) {
            String traceId = f.sampleTraceIds().get(0);
            try {
                Instant to = clock.instant();
                TraceTimeline t = traces.trace(traceId, to.minus(Duration.ofHours(24)), to);
                add(evidence, "TRACE", traceId, t.startedAt(), "One failed request went through " + String.join(" -> ",
                        t.components()) + " in " + t.durationMs() + "ms, " + t.steps().size() + " steps, "
                        + t.calls().size() + " calls to dependencies (" + t.calls().stream().filter(c -> !c.outcome().equals("OK"))
                                .count() + " failed)", 5, "shows the path of one request");
                known.addAll(t.components());
                t.calls().forEach(c -> known.add(c.target()));
                if (t.divergence() != null) {
                    add(evidence, "DIVERGENCE", traceId, t.steps().get(t.divergence().step()).timestamp(),
                            t.divergence().type() + " at " + t.divergence().where() + ": " + t.divergence().detail(), 25,
                            "the trace shows where the request went wrong");
                }
            } catch (RuntimeException e) {
                log.info("No trace evidence for finding {}: {}", rank, e.getMessage());
            }
        }

        SourceLocation at = f.source();
        if (at != null && at.found()) {
            add(evidence, "SOURCE", at.path() + ":" + at.line(), null, "Thrown at " + at.path() + " line " + at.line()
                    + " (`" + at.code() + "`)" + (at.lineLastChanged() == null ? "" : "; that line was last changed by commit "
                            + at.lineLastChanged().hash() + " at " + at.lineLastChanged().time()), 10,
                    "the failing line in the deployed code");
        }

        // What each commit says about a change request, to tie the two together
        Map<String, String> commitOfChange = new LinkedHashMap<>();
        if (failure != null) {
            try {
                for (Suspect s : suspects.find(f.component(), failure, LOOKBACK, at != null && at.found() ? at.className() : null)
                        .suspects().stream().limit(5).toList()) {
                    int points = s.score() >= 80 ? 25 : s.score() >= 60 ? 15 : s.score() >= 40 ? 8 : 3;
                    if (s.changeNumber() != null) {
                        commitOfChange.put(s.changeNumber(), s.hash());
                    }
                    add(evidence, "COMMIT", s.hash(), s.time(), "Commit " + s.hash() + " by " + s.author() + ", \""
                            + s.message() + "\", " + s.minutesBeforeFailure() + " min before the failures began; changes "
                            + String.join(", ", s.files().stream().limit(3).toList())
                            + (s.changeNumber() == null ? "" : "; names " + s.changeNumber()), points,
                            "suspect score " + s.score() + ": " + String.join("; ", s.reasons()));
                }
            } catch (RuntimeException e) {
                log.info("No commit evidence for finding {}: {}", rank, e.getMessage());
            }
            try {
                Instant to = clock.instant();
                for (TimedChange c : changes.search(List.of(f.component()), failure.minus(LOOKBACK), to, failure).changes()
                        .stream().limit(5).toList()) {
                    Long minutes = c.minutesBeforeFailure();
                    int points = "AFTER_FAILURE".equals(c.timing()) ? 0 : minutes != null && minutes <= 360 ? 20 : 10;
                    add(evidence, "CHANGE", c.change().number(), c.change().startedAt(), c.change().number() + " ("
                            + c.change().type() + ", " + c.change().state() + "): \"" + c.change().shortDescription() + "\" "
                            + c.timingNote() + (commitOfChange.containsKey(c.change().number()) ? "; named by commit "
                                    + commitOfChange.get(c.change().number()) : ""), points,
                            "AFTER_FAILURE".equals(c.timing()) ? "started after the failures: it cannot be the cause"
                                    : minutes != null && minutes <= 360 ? "deployed within 6 hours before the failures"
                                            : "deployed before the failures, but more than 6 hours earlier");
                }
            } catch (RuntimeException e) {
                log.info("No change evidence for finding {}: {}", rank, e.getMessage());
            }
        }

        for (KnowledgeRef k : f.knowledge() == null ? List.<KnowledgeRef>of() : f.knowledge()) {
            boolean runbook = KnowledgeService.RUNBOOK.equals(k.type());
            add(evidence, runbook ? "RUNBOOK" : "PAST_RCA", k.id(), k.date(), (runbook ? "Runbook " : "Past RCA ") + k.id()
                    + " \"" + k.title() + "\" (" + k.matchedBy() + " match): " + k.summary(), 8,
                    runbook ? "a runbook written for this problem" : "it happened before");
        }

        String now = clock.instant().toString();
        String impact = f.count() + " failed " + f.transaction() + " requests in " + f.component() + " between "
                + f.firstSeen() + " and " + f.lastSeen() + " (" + f.timePattern() + "), "
                + f.percentOfComponent() + "% of that component's failures in the report";
        return store.save(new Investigation("INV-" + UUID.randomUUID().toString().substring(0, 8), report.id(), rank,
                f.signatureId(), f.component(), f.transaction(), f.exception(), f.firstSeen(), List.copyOf(known), impact,
                now, caller.client(), List.copyOf(evidence), 0, List.of(), null, null,
                List.of(new Event(now, caller.client(), "COLLECTED", evidence.size() + " evidence items for finding " + rank))));
    }

    /**
     * Checks, scores and ranks a model's hypotheses and replaces the ones recorded before.
     *
     * @throws IllegalArgumentException with what to correct, when a hypothesis is not acceptable
     * @throws NotFoundException when there is no such investigation
     */
    public Investigation recordHypotheses(String investigationId, List<HypothesisInput> inputs) {
        Investigation inv = get(investigationId);
        if (inputs == null || inputs.isEmpty() || inputs.size() > MAX_HYPOTHESES) {
            throw new IllegalArgumentException("'hypotheses' must have 1 to " + MAX_HYPOTHESES + " entries");
        }
        if (inv.pullRequest() != null) {
            throw new IllegalArgumentException("'investigationId' " + inv.id() + " already has pull request "
                    + inv.pullRequest().number() + ": start a new investigation to revise the hypotheses");
        }
        Map<String, Evidence> byId = new LinkedHashMap<>();
        inv.evidence().forEach(e -> byId.put(e.id(), e));
        String now = clock.instant().toString();

        List<Hypothesis> scored = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            HypothesisInput in = inputs.get(i);
            String which = "hypotheses[" + i + "]";
            if (in == null || in.statement() == null || in.statement().isBlank()) {
                throw new IllegalArgumentException("'statement' of " + which + " is required");
            }
            if (in.statement().length() > MAX_STATEMENT) {
                throw new IllegalArgumentException("'statement' of " + which + " may be at most " + MAX_STATEMENT + " characters");
            }
            if (in.evidenceIds() == null || in.evidenceIds().isEmpty()) {
                throw new IllegalArgumentException("'evidenceIds' of " + which + " is required: cite at least one of "
                        + byId.keySet());
            }
            List<Evidence> cited = new ArrayList<>();
            for (String id : new LinkedHashSet<>(in.evidenceIds())) {
                Evidence e = byId.get(id == null ? "" : id.strip().toUpperCase());
                if (e == null) {
                    throw new IllegalArgumentException("'evidenceIds' of " + which + " cites " + id
                            + ", which is not in this investigation. Evidence: " + byId.keySet());
                }
                cited.add(e);
            }
            requireGrounded(which, in.statement(), cited);
            String confidence = in.confidence() == null || in.confidence().isBlank() ? null
                    : in.confidence().strip().toUpperCase();
            scored.add(score(masker.mask(in.statement().strip()), cited, confidence, caller.client(), now));
        }
        // A plan was for a hypothesis of the old set
        return store.save(inv.with(inv.evidence(), inv.revision() + 1, ranked(scored), null, null, new Event(now,
                caller.client(), "HYPOTHESES_RECORDED", scored.size() + " hypotheses"
                        + (inv.fixPlan() == null ? "" : "; the earlier fix plan was dropped"))));
    }

    private static List<Hypothesis> ranked(List<Hypothesis> scored) {
        List<Hypothesis> sorted = new ArrayList<>(scored);
        sorted.sort(Comparator.comparingInt(Hypothesis::score).reversed());
        List<Hypothesis> ranked = new ArrayList<>();
        for (Hypothesis h : sorted) {
            ranked.add(new Hypothesis(ranked.size() + 1, h.statement(), h.evidenceIds(), h.modelConfidence(), h.score(),
                    h.level(), h.scoreBreakdown(), h.recordedBy(), h.recordedAt()));
        }
        return ranked;
    }

    /**
     * A developer sets a piece of evidence aside ("that change is unrelated") or brings it
     * back. It stays listed, counts for nothing, and every hypothesis is scored again.
     */
    public Investigation excludeEvidence(String investigationId, String evidenceId, boolean exclude, String reason) {
        Investigation inv = get(investigationId);
        String wanted = evidenceId == null ? "" : evidenceId.strip().toUpperCase();
        if (inv.evidence().stream().noneMatch(e -> e.id().equals(wanted))) {
            throw new IllegalArgumentException("'evidenceId' " + evidenceId + " is not in this investigation. Evidence: "
                    + inv.evidence().stream().map(Evidence::id).toList());
        }
        if (exclude && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("'reason' is required: say why the evidence does not apply");
        }
        if (reason != null && reason.length() > MAX_TEXT) {
            throw new IllegalArgumentException("'reason' may be at most " + MAX_TEXT + " characters");
        }
        String why = exclude ? masker.mask(reason.strip()) : null;
        List<Evidence> evidence = inv.evidence().stream().map(e -> !e.id().equals(wanted) ? e
                : new Evidence(e.id(), e.type(), e.ref(), e.time(), e.summary(), e.points(), e.why(), exclude, why, e.addedBy()))
                .toList();
        String now = clock.instant().toString();
        // An approval was given for the plan as the evidence then stood
        FixPlan plan = inv.fixPlan() == null || inv.pullRequest() != null || !"APPROVED".equals(inv.fixPlan().status())
                ? inv.fixPlan() : inv.fixPlan().decided("PROPOSED", null, null, null);
        return store.save(inv.with(evidence, inv.revision(), rescored(inv.hypotheses(), evidence), plan, inv.pullRequest(),
                new Event(now, caller.client(), exclude ? "EVIDENCE_EXCLUDED" : "EVIDENCE_RESTORED", wanted
                        + (why == null ? "" : ": " + why) + (plan != inv.fixPlan() ? "; the fix plan needs approval again" : ""))));
    }

    /** Something the developer knows that no tool returned. It can be cited, and adds nothing to a score. */
    public Investigation addNote(String investigationId, String note) {
        Investigation inv = get(investigationId);
        if (note == null || note.isBlank() || note.length() > MAX_TEXT) {
            throw new IllegalArgumentException("'note' is required, at most " + MAX_TEXT + " characters");
        }
        List<Evidence> evidence = new ArrayList<>(inv.evidence());
        String now = clock.instant().toString();
        Evidence added = new Evidence("E" + (evidence.size() + 1), "NOTE", null, now, masker.mask(note.strip()), 0,
                "said by a person, not verified by the service", false, null, caller.client());
        evidence.add(added);
        return store.save(inv.with(evidence, inv.revision(), inv.hypotheses(), inv.fixPlan(), inv.pullRequest(),
                new Event(now, caller.client(), "NOTE_ADDED", added.id())));
    }

    private List<Hypothesis> rescored(List<Hypothesis> hypotheses, List<Evidence> evidence) {
        Map<String, Evidence> byId = new LinkedHashMap<>();
        evidence.forEach(e -> byId.put(e.id(), e));
        return ranked(hypotheses.stream().map(h -> score(h.statement(), h.evidenceIds().stream().map(byId::get).toList(),
                h.modelConfidence(), h.recordedBy(), h.recordedAt())).toList());
    }

    /**
     * Records the plan for one hypothesis. The model writes the plan; the service checks what
     * can be checked: that it names nothing the evidence does not contain, that the blast
     * radius stays within the components this failure is known to involve, and that each edit
     * matches the deployed code line for line.
     */
    public Investigation recordFixPlan(String investigationId, Integer hypothesisRank, FixPlanInput in) {
        Investigation inv = get(investigationId);
        if (inv.hypotheses().isEmpty()) {
            throw new IllegalArgumentException("'investigationId' " + inv.id() + " has no hypotheses yet: record them "
                    + "with rcaRecordHypotheses first");
        }
        if (inv.pullRequest() != null) {
            throw new IllegalArgumentException("'investigationId' " + inv.id() + " already has pull request "
                    + inv.pullRequest().number() + ": its plan can no longer change");
        }
        if (hypothesisRank == null || hypothesisRank < 1 || hypothesisRank > inv.hypotheses().size()) {
            throw new IllegalArgumentException("'hypothesisRank' must be between 1 and " + inv.hypotheses().size());
        }
        if (in == null) {
            throw new IllegalArgumentException("'plan' is required");
        }
        String summary = text("summary", in.summary(), 200);
        List<String> steps = texts("steps", in.steps());
        String rollback = text("rollback", in.rollback(), MAX_TEXT);
        List<String> tests = texts("testPlan", in.testPlan());
        String radius = text("blastRadius", in.blastRadius(), MAX_TEXT);
        if (in.blastRadiusComponents() == null || in.blastRadiusComponents().isEmpty()) {
            throw new IllegalArgumentException("'blastRadiusComponents' is required: one or more of " + inv.knownComponents());
        }
        for (String c : in.blastRadiusComponents()) {
            if (!inv.knownComponents().contains(c)) {
                throw new IllegalArgumentException("'blastRadiusComponents' names " + c + ", which this failure is not "
                        + "known to involve. Known: " + inv.knownComponents() + ". If it matters, have the developer add a note");
            }
        }
        List<Evidence> usable = inv.evidence().stream().filter(e -> !e.excluded()).toList();
        requireGrounded("the plan", String.join("\n", summary, String.join("\n", steps), rollback,
                String.join("\n", tests), radius), usable);

        List<Edit> edits = new ArrayList<>();
        List<EditInput> wanted = in.edits() == null ? List.of() : in.edits();
        if (wanted.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("'edits' may have at most " + MAX_ITEMS + " entries");
        }
        for (int i = 0; i < wanted.size(); i++) {
            EditInput e = wanted.get(i);
            String which = "edits[" + i + "]";
            if (e == null || e.path() == null || e.line() == null || e.oldCode() == null || e.newCode() == null
                    || e.newCode().isBlank()) {
                throw new IllegalArgumentException("'path', 'line', 'oldCode' and 'newCode' of " + which + " are required");
            }
            if (e.newCode().contains("\n") || e.newCode().length() > 300 || e.path().contains("..")) {
                throw new IllegalArgumentException("'newCode' of " + which + " must be one line of at most 300 characters");
            }
            String deployed = source.lineAt(inv.component(), e.path().strip(), e.line()).orElseThrow(() ->
                    new IllegalArgumentException("'path' of " + which + ": line " + e.line() + " of " + e.path()
                            + " cannot be read from the deployed code, so the edit cannot be checked"));
            if (!deployed.strip().equals(e.oldCode().strip())) {
                throw new IllegalArgumentException("'oldCode' of " + which + " does not match the deployed code. Line "
                        + e.line() + " of " + e.path() + " is: " + deployed.strip());
            }
            if (deployed.strip().equals(e.newCode().strip())) {
                throw new IllegalArgumentException("'newCode' of " + which + " is the same as the line is now");
            }
            edits.add(new Edit(e.path().strip(), e.line(), deployed.strip(), e.newCode().strip()));
        }

        Hypothesis h = inv.hypotheses().get(hypothesisRank - 1);
        List<String> warnings = new ArrayList<>();
        if (hypothesisRank != 1) {
            warnings.add("The plan is for hypothesis " + hypothesisRank + ", not the best supported one");
        }
        if (!"HIGH".equals(h.level())) {
            warnings.add("The hypothesis is only " + h.level() + " (" + h.score() + "): confirm the cause before fixing it");
        }
        if (edits.isEmpty()) {
            warnings.add("No edits: the plan is a procedure, and a pull request made from it will carry no change");
        }
        String mitigation = usable.stream().filter(e -> e.type().equals("RUNBOOK")).map(Evidence::summary).findFirst()
                .orElse(null);
        String now = clock.instant().toString();
        FixPlan plan = new FixPlan(hypothesisRank, h.statement(), summary, steps, rollback, tests,
                new BlastRadius(List.copyOf(in.blastRadiusComponents()), radius), List.copyOf(edits), mitigation,
                List.copyOf(warnings), "PROPOSED", caller.client(), now, null, null, null);
        return store.save(inv.with(inv.evidence(), inv.revision(), inv.hypotheses(), plan, null,
                new Event(now, caller.client(), "FIX_PLAN_PROPOSED", summary)));
    }

    /** A person's decision. With security on, it cannot be the client that proposed the plan. */
    public Investigation decideFixPlan(String investigationId, boolean approve, String comment) {
        Investigation inv = get(investigationId);
        if (inv.fixPlan() == null || !"PROPOSED".equals(inv.fixPlan().status())) {
            throw new IllegalArgumentException("'investigationId' " + inv.id() + " has no fix plan waiting for a decision"
                    + (inv.fixPlan() == null ? "" : ": it is " + inv.fixPlan().status()));
        }
        String who = caller.client();
        if (approve && !security.off() && who.equals(inv.fixPlan().proposedBy())) {
            throw new Caller.DeniedException("The fix plan was proposed by '" + who + "' and must be approved by someone else");
        }
        String now = clock.instant().toString();
        String said = comment == null || comment.isBlank() ? null
                : masker.mask(comment.strip().substring(0, Math.min(comment.strip().length(), MAX_TEXT)));
        return store.save(inv.with(inv.evidence(), inv.revision(), inv.hypotheses(),
                inv.fixPlan().decided(approve ? "APPROVED" : "REJECTED", who, now, said), null,
                new Event(now, who, approve ? "FIX_PLAN_APPROVED" : "FIX_PLAN_REJECTED", said)));
    }

    /**
     * Opens a draft pull request for an approved plan. Asking again returns the same one.
     *
     * @throws IllegalArgumentException when the plan has not been approved by a person
     */
    public Investigation draftPullRequest(String investigationId) {
        Investigation inv = get(investigationId);
        if (inv.pullRequest() != null) {
            return inv;
        }
        if (inv.fixPlan() == null || !"APPROVED".equals(inv.fixPlan().status())) {
            throw new IllegalArgumentException("'investigationId' " + inv.id() + (inv.fixPlan() == null
                    ? " has no fix plan: record one with rcaRecordFixPlan"
                    : " has a fix plan that is " + inv.fixPlan().status() + ", not APPROVED") + ". A person approves a "
                    + "plan outside this conversation; a pull request can be drafted only after that");
        }
        String branch = "rca/" + inv.id().toLowerCase();
        String title = "[" + inv.id() + "] " + inv.fixPlan().summary();
        PullRequestClient.Created created = pullRequests.open(new PullRequestClient.Draft(inv.id(), branch, title,
                pullRequestBody(inv), inv.fixPlan().edits()));
        String now = clock.instant().toString();
        return store.save(inv.with(inv.evidence(), inv.revision(), inv.hypotheses(), inv.fixPlan(),
                new PullRequest(created.number(), created.url(), branch, title, created.system(), now, caller.client()),
                new Event(now, caller.client(), "PULL_REQUEST_DRAFTED", created.number() + " on " + created.system())));
    }

    /** Written by code from the investigation, so a reviewer sees the evidence and who approved. */
    private static String pullRequestBody(Investigation inv) {
        FixPlan p = inv.fixPlan();
        Hypothesis h = inv.hypotheses().get(p.hypothesisRank() - 1);
        StringBuilder b = new StringBuilder();
        b.append("Draft from RCA investigation ").append(inv.id()).append(". Review before marking ready.\n\n");
        b.append("## Problem\n").append(inv.observedImpact()).append("\n\n");
        b.append("## Cause (").append(h.level()).append(", evidence score ").append(h.score()).append(")\n")
                .append(h.statement()).append("\n\n");
        b.append("## Evidence\n");
        inv.evidence().stream().filter(e -> h.evidenceIds().contains(e.id()) && !e.excluded())
                .forEach(e -> b.append("- ").append(e.id()).append(" ").append(e.type()).append(": ").append(e.summary()).append("\n"));
        b.append("\n## Fix\n");
        for (int i = 0; i < p.steps().size(); i++) {
            b.append(i + 1).append(". ").append(p.steps().get(i)).append("\n");
        }
        b.append("\n## Changes in this pull request\n");
        if (p.edits().isEmpty()) {
            b.append("None: the plan is a procedure.\n");
        }
        p.edits().forEach(e -> b.append("- `").append(e.path()).append("` line ").append(e.line()).append(": `")
                .append(e.oldCode()).append("` -> `").append(e.newCode()).append("`\n"));
        b.append("\n## Test plan\n");
        p.testPlan().forEach(t -> b.append("- [ ] ").append(t).append("\n"));
        b.append("\n## Blast radius\n").append(String.join(", ", p.blastRadius().components())).append(": ")
                .append(p.blastRadius().description()).append("\n\n");
        b.append("## Rollback\n").append(p.rollback()).append("\n\n");
        if (!p.warnings().isEmpty()) {
            b.append("## For the reviewer\n");
            p.warnings().forEach(w -> b.append("- ").append(w).append("\n"));
            b.append("\n");
        }
        b.append("Plan proposed by ").append(p.proposedBy()).append(", approved by ").append(p.decidedBy()).append(" at ")
                .append(p.decidedAt()).append(".\n");
        return b.toString();
    }

    private String text(String name, String value, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("'" + name + "' is required");
        }
        if (value.length() > max) {
            throw new IllegalArgumentException("'" + name + "' may be at most " + max + " characters");
        }
        return masker.mask(value.strip());
    }

    private List<String> texts(String name, List<String> values) {
        if (values == null || values.isEmpty() || values.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("'" + name + "' must have 1 to " + MAX_ITEMS + " entries");
        }
        List<String> out = new ArrayList<>();
        for (String v : values) {
            out.add(text(name, v, 500));
        }
        return List.copyOf(out);
    }

    /** A change number, document id or commit hash in the statement must come from the evidence cited. */
    private static void requireGrounded(String which, String statement, List<Evidence> cited) {
        Set<String> refs = new LinkedHashSet<>();
        for (Evidence e : cited) {
            if (e.ref() != null) {
                refs.add(e.ref());
            }
            Matcher inSummary = NAMED.matcher(e.summary());
            while (inSummary.find()) {
                refs.add(inSummary.group(1));
            }
            Matcher hashInSummary = HASH.matcher(e.summary());
            while (hashInSummary.find()) {
                refs.add(hashInSummary.group());
            }
        }
        Matcher named = NAMED.matcher(statement);
        while (named.find()) {
            if (!refs.contains(named.group(1))) {
                throw new IllegalArgumentException(subject(which) + " names " + named.group(1)
                        + ", which is not in the evidence it cites. Cite the evidence that contains it, or remove it");
            }
        }
        Matcher hash = HASH.matcher(statement);
        while (hash.find()) {
            String h = hash.group();
            if (refs.stream().noneMatch(r -> r != null && (r.contains(h) || h.startsWith(r)))) {
                throw new IllegalArgumentException(subject(which) + " names commit " + h
                        + ", which is not in the evidence it cites. Cite the evidence that contains it, or remove it");
            }
        }
    }

    /** The best item of each type counts once; a change and the commit that names it corroborate each other. */
    private static Hypothesis score(String statement, List<Evidence> cited, String modelConfidence, String by, String at) {
        Map<String, Evidence> bestOfType = new LinkedHashMap<>();
        List<String> breakdown = new ArrayList<>();
        for (Evidence e : cited) {
            if (e.excluded()) {
                breakdown.add("+0 " + e.id() + " " + e.type() + ": set aside by a developer (" + e.excludedReason() + ")");
            } else {
                bestOfType.merge(e.type(), e, (a, b) -> b.points() > a.points() ? b : a);
            }
        }
        int score = 0;
        for (Evidence e : bestOfType.values()) {
            score += e.points();
            breakdown.add("+" + e.points() + " " + e.id() + " " + e.type() + ": " + e.why());
        }
        Evidence change = bestOfType.get("CHANGE");
        Evidence commit = bestOfType.get("COMMIT");
        if (change != null && commit != null && change.points() > 0
                && (commit.summary().contains(change.ref()) || change.summary().contains(commit.ref()))) {
            score += 10;
            breakdown.add("+10 " + commit.id() + " and " + change.id() + " are the same change: the commit names the change request");
        }
        score = Math.min(95, score);
        return new Hypothesis(0, statement, cited.stream().map(Evidence::id).toList(), modelConfidence, score,
                score >= 70 ? "HIGH" : score >= 40 ? "MEDIUM" : "LOW", breakdown, by, at);
    }

    private static String subject(String which) {
        return which.startsWith("hypotheses") ? "'statement' of " + which : "'plan': " + which;
    }

    private static void add(List<Evidence> evidence, String type, String ref, String time, String summary, int points,
            String why) {
        evidence.add(new Evidence("E" + (evidence.size() + 1), type, ref, time, summary, points, why, false, null, null));
    }

    private static String shortName(String exception) {
        return exception == null ? "failures" : exception.substring(exception.lastIndexOf('.') + 1);
    }

    private static Instant time(String iso) {
        try {
            return iso == null ? null : Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
