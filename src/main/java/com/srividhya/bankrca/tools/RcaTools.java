package com.srividhya.bankrca.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.srividhya.bankrca.knowledge.KnowledgeHit;
import com.srividhya.bankrca.knowledge.KnowledgeService;
import com.srividhya.bankrca.rca.RcaReport;
import com.srividhya.bankrca.rca.RcaReport.Finding;
import com.srividhya.bankrca.rca.RcaService;
import com.srividhya.bankrca.security.Scopes;

/**
 * The functions an assistant may call. Design rules:
 * - each does one thing, with named, described parameters and a typed result
 * - all are read-only and return data that is already masked
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

    /**
     * @param verdict MATCH when at least one document really matches, NO_MATCH when the best is only a weak resemblance
     * @param hits best first; with NO_MATCH these are the nearest documents, for reference only
     */
    public record KnowledgeSearchResult(String verdict, String searchedBy, List<KnowledgeHit> hits) {
    }

    private final RcaService rca;
    private final KnowledgeService knowledge;
    private final ToolAudit audit;

    public RcaTools(RcaService rca, KnowledgeService knowledge, ToolAudit audit) {
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
