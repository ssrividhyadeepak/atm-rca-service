package com.srividhya.bankrca.rca;

import static com.srividhya.bankrca.rca.RcaTestData.day;
import static com.srividhya.bankrca.rca.RcaTestData.signature;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.srividhya.bankrca.correlation.CorrelationResult;
import com.srividhya.bankrca.correlation.CorrelationResult.FailureSignature;
import com.srividhya.bankrca.rca.RcaReport.Finding;
import com.srividhya.bankrca.knowledge.KeywordKnowledgeIndex;
import com.srividhya.bankrca.knowledge.KnowledgeHit;
import com.srividhya.bankrca.knowledge.KnowledgeService;
import com.srividhya.bankrca.rca.StackTraces.Frame;
import com.srividhya.bankrca.security.PiiMasker;
import com.srividhya.bankrca.source.SourceLocation;
import com.srividhya.bankrca.source.SourceRepository;
import com.srividhya.bankrca.storage.InMemoryRcaStore;

/** New versus recurring, and one report per day. */
class RcaServiceTest {

    /** These tests are about history and saving; nothing is looked up in source code. */
    static final SourceRepository NO_SOURCE = new SourceRepository() {
        @Override
        public String description() {
            return "none";
        }

        @Override
        public SourceLocation locate(String component, Frame frame) {
            return SourceLocation.notFound(frame.className(), frame.method(), frame.line(), "no source in this test");
        }
    };

    private final InMemoryRcaStore store = new InMemoryRcaStore();
    /** An empty knowledge base: these tests add to it only by saving reports. */
    private final KnowledgeService knowledge = new KnowledgeService(new KeywordKnowledgeIndex(), new PiiMasker(),
            Path.of("build/no-knowledge-here"));
    @TempDir
    static Path dir;

    private final RcaInputFiles files = new RcaInputFiles(dir);
    private final RcaService service = new RcaService(
            new RuleBasedRcaAnalyzer(Clock.fixed(Instant.parse("2026-10-03T00:05:00Z"), ZoneOffset.UTC)),
            new RcaInputBuilder(NO_SOURCE), files, store, knowledge);

    private static final FailureSignature TIMEOUT = signature("timeout", "pay-p1", "com.acme.HostTimeoutException",
            "timed out", null, 64, "BURST", 2, 6, 40);
    private static final FailureSignature DEFECT = signature("defect", "deposit-p1", "java.lang.NullPointerException",
            "npe", null, 22, "STEADY", 2, 6, 13);

    private static CorrelationResult window(String from, String to, FailureSignature... signatures) {
        return day(from, to, List.of(signatures), List.of());
    }

    @Test
    void aProblemStaysNewForEveryRunOverTheWindowItFirstAppearedIn() {
        CorrelationResult firstDay = window("2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z", TIMEOUT);

        RcaReport first = service.analyzeAndSave(firstDay);
        // The job runs every 15 minutes over a rolling day: the second run must not call it recurring
        RcaReport again = service.analyzeAndSave(window("2026-10-01T00:15:00Z", "2026-10-02T00:15:00Z", TIMEOUT));

        assertThat(first.findings().get(0).status()).isEqualTo("NEW");
        assertThat(again.findings().get(0).status()).isEqualTo("NEW");
    }

    @Test
    void aProblemSeenBeforeTheWindowIsRecurringWithTheDateItWasFirstSeen() {
        service.analyzeAndSave(window("2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z", TIMEOUT));

        // Two days later: the timeout (first seen 2026-10-01T13:00) is back, the defect is new
        RcaReport later = service.analyzeAndSave(window("2026-10-02T14:00:00Z", "2026-10-03T14:00:00Z", TIMEOUT, DEFECT));

        Finding timeout = later.findings().stream().filter(f -> f.signatureId().equals("timeout")).findFirst().orElseThrow();
        Finding defect = later.findings().stream().filter(f -> f.signatureId().equals("defect")).findFirst().orElseThrow();
        assertThat(timeout.status()).isEqualTo("RECURRING");
        assertThat(timeout.knownSince()).isEqualTo("2026-10-01T13:00:00Z");
        assertThat(defect.status()).isEqualTo("NEW");
        assertThat(later.headline()).startsWith("2 distinct problems in 86 failure events, 1 new.");
        assertThat(later.markdown()).contains("RECURRING (first seen 2026-10-01T13:00:00Z)");
    }

    @Test
    void keepsOneReportPerDayAndTheLatestRunWins() {
        service.analyzeAndSave(window("2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z", TIMEOUT));
        service.analyzeAndSave(window("2026-10-01T10:00:00Z", "2026-10-02T10:00:00Z", TIMEOUT, DEFECT));
        service.analyzeAndSave(window("2026-10-02T00:00:00Z", "2026-10-03T00:00:00Z", DEFECT));

        assertThat(service.reports(10)).extracting(RcaReport::id).containsExactly("2026-10-03", "2026-10-02");
        assertThat(service.reports(10).get(1).findings()).as("the later run of 2 October").hasSize(2);
        assertThat(service.latest().orElseThrow().id()).isEqualTo("2026-10-03");
    }

    @Test
    void savesTheInputAndTiesTheReportToIt() throws Exception {
        RcaReport report = service.analyzeAndSave(window("2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z", TIMEOUT, DEFECT));

        Path dayFile = dir.resolve("daily-rca-input-2026-10-02.json");
        String saved = Files.readString(dayFile);
        assertThat(Files.readString(dir.resolve("daily-rca-input.json"))).isEqualTo(saved);
        assertThat(report.inputSchemaVersion()).isEqualTo("1.2");
        assertThat(report.inputHash()).isEqualTo(RcaInputFiles.hash(saved)).matches("[0-9a-f]{64}");
        assertThat(report.markdown()).contains("Input: daily-rca-input-2026-10-02.json, schema 1.2, sha256 "
                + report.inputHash().substring(0, 12));
        assertThat(service.latestInput().orElseThrow().id()).isEqualTo("2026-10-02");

        // Replaying the saved file gives the same findings, with nothing read from Splunk
        RcaReport replayed = service.analyze(files.fromJson(saved));
        assertThat(replayed.findings()).isEqualTo(report.findings());
        assertThat(replayed.headline()).isEqualTo(report.headline());
    }

    @Test
    void savedReportsBecomeSearchableHistory() {
        service.analyzeAndSave(window("2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z", TIMEOUT));

        List<KnowledgeHit> hits = knowledge.search("HostTimeoutException in pay-p1", KnowledgeService.PAST_RCA, 3, null);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).id()).isEqualTo("RCA-2026-10-02-timeout");
        assertThat(hits.get(0).matchedBy()).isEqualTo("EXACT");
        assertThat(hits.get(0).metadata()).containsEntry("date", "2026-10-02").containsEntry("component", "pay-p1");
        // A run on the same day does not find itself; the next day does
        assertThat(knowledge.search("HostTimeoutException", KnowledgeService.PAST_RCA, 3, "2026-10-02")).isEmpty();
        assertThat(knowledge.search("HostTimeoutException", KnowledgeService.PAST_RCA, 3, "2026-10-03")).hasSize(1);
    }

    @Test
    void refusesAnInputOfAnotherSchemaVersion() {
        RcaInput future = new RcaInput("2026-10-02", "2.0", window("2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z", TIMEOUT),
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), 0);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.analyze(future))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported schemaVersion '2.0'; this service reads 1.0 and 1.1 and 1.2");
    }

    @Test
    void stillReadsAnInputOfTheEarlierVersionThatHadNoSources() {
        // As a file saved before 'sources' existed would be read back
        RcaInput old = files.fromJson(files.toJson(RcaInput.of(window("2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z",
                TIMEOUT), java.util.Map.of())).replace("\"1.2\"", "\"1.0\"").replaceFirst("\"sources\" : \\{ },?", "")
                .replaceFirst("\"knowledge\" : \\{ },?", ""));

        assertThat(old.schemaVersion()).isEqualTo("1.0");
        assertThat(old.sources()).isNull();
        assertThat(old.knowledge()).isNull();
        assertThat(service.analyze(old).findings()).hasSize(1);
    }
}
