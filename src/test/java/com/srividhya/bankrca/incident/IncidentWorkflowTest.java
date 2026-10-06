package com.srividhya.bankrca.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The incident workflow over HTTP, on the sample day, with the mock ticket system: from a
 * finding to a draft, a person's decision, and the incident. Each test uses its own finding,
 * because one problem gets one incident.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "rca.incident.mock-file=build/test-data/incidents/workflow-test.jsonl")
@ActiveProfiles("test")
class IncidentWorkflowTest {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = new JsonMapper();

    @BeforeEach
    void analyse() throws Exception {
        send("POST", "/api/rca", null);
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    @Test
    void fromFindingToDraftToApprovedIncident() throws Exception {
        HttpResponse<String> created = send("POST", "/api/incidents/drafts", "{\"rank\":1}");

        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode draft = body(created).get("draft");
        String id = draft.get("id").asString();
        assertThat(id).matches("DRAFT-[0-9a-f]{8}");
        assertThat(body(created).get("message").asString()).contains("needs a person's approval");
        assertThat(draft.get("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.get("shortDescription").asString())
                .isEqualTo("[RCA] HostAuthTimeoutException in withdrawal-p1: 64 failures (BURST)");
        // Built from the finding: priority from its severity, the group from its runbook's owner
        assertThat(draft.get("priority").asString()).isEqualTo("2 - High");
        assertThat(draft.get("assignmentGroup").asString()).isEqualTo("Bank Platform Engineering");
        assertThat(draft.get("component").asString()).isEqualTo("withdrawal-p1");
        assertThat(draft.get("suspectCommit").asString()).isEqualTo("a9aa3e8f10");
        assertThat(draft.get("runbook").asString()).isEqualTo("RB-001");
        assertThat(draft.get("description").asString()).contains("report 2026-10-02, finding 1")
                .contains("Likely cause (confidence HIGH):").contains("Next step: Review commit a9aa3e8f10 first.")
                .contains("- 64 events, 85.3% of withdrawal-p1's failures").contains("Runbook RB-001")
                .doesNotContain("4111111111111111");
        assertThat(draft.get("incidentNumber").isNull()).isTrue();
        // Nothing has reached the ticket system
        assertThat(mockFile()).doesNotContain(id);

        JsonNode submitted = body(send("POST", "/api/incidents/" + id + "/approve", "{\"comment\":\"Confirmed with on-call\"}"));

        assertThat(submitted.get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(submitted.get("incidentNumber").asString()).matches("INC\\d{7}");
        assertThat(submitted.get("incidentSystem").asString()).isEqualTo("mock");
        assertThat(submitted.get("decidedBy").asString()).isEqualTo("local");
        assertThat(submitted.get("decisionComment").asString()).isEqualTo("Confirmed with on-call");
        assertThat(mockFile()).contains("\"correlation_id\":\"" + id + "\"")
                .contains("\"number\":\"" + submitted.get("incidentNumber").asString() + "\"");
        assertThat(body(send("GET", "/api/incidents/" + id, null)).get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(send("GET", "/api/incidents", null).body()).contains(id);

        // Each step is on the audit trail
        List<String> trail = Files.readAllLines(Path.of("build/test-data/logs/audit.log")).stream()
                .filter(l -> l.contains("\"draft\":\"" + id + "\"")).toList();
        assertThat(trail).hasSize(3);
        assertThat(trail.get(0)).contains("\"action\":\"incident.draft\"").contains("\"client\":\"local\"");
        assertThat(trail.get(1)).contains("\"action\":\"incident.approve\"").contains("\"status\":\"APPROVED\"");
        assertThat(trail.get(2)).contains("\"action\":\"incident.submit\"").contains("\"incident\":\"INC");

        // Once submitted it cannot be approved, rejected or submitted again
        assertThat(send("POST", "/api/incidents/" + id + "/approve", "{}").statusCode()).isEqualTo(409);
        assertThat(send("POST", "/api/incidents/" + id + "/reject", "{\"reason\":\"x\"}").statusCode()).isEqualTo(409);
        HttpResponse<String> again = send("POST", "/api/incidents/" + id + "/submit", null);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("is SUBMITTED (" + submitted.get("incidentNumber").asString() + ")");
    }

    @Test
    void oneProblemGetsOneIncident() throws Exception {
        JsonNode first = body(send("POST", "/api/incidents/drafts", "{\"rank\":2}"));

        HttpResponse<String> second = send("POST", "/api/incidents/drafts", "{\"rank\":2}");

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(body(second).get("created").asBoolean()).isFalse();
        assertThat(body(second).get("message").asString()).contains("already exists (DRAFT)");
        assertThat(body(second).get("draft").get("id").asString()).isEqualTo(first.get("draft").get("id").asString());
        // A second analysis of the same day does not change that
        send("POST", "/api/rca", null);
        assertThat(body(send("POST", "/api/incidents/drafts", "{\"rank\":2}")).get("created").asBoolean()).isFalse();
    }

    @Test
    void aRejectedDraftSendsNothingAndTheProblemCanBeDraftedAgain() throws Exception {
        String id = body(send("POST", "/api/incidents/drafts", "{\"rank\":3}")).get("draft").get("id").asString();

        HttpResponse<String> noReason = send("POST", "/api/incidents/" + id + "/reject", "{}");
        assertThat(noReason.statusCode()).isEqualTo(400);
        assertThat(noReason.body()).contains("'reason' is required");
        JsonNode rejected = body(send("POST", "/api/incidents/" + id + "/reject", "{\"reason\":\"Core banking already fixed it\"}"));

        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("decisionComment").asString()).isEqualTo("Core banking already fixed it");
        assertThat(rejected.get("incidentNumber").isNull()).isTrue();
        assertThat(mockFile()).doesNotContain(id);
        assertThat(send("POST", "/api/incidents/" + id + "/approve", "{}").statusCode()).isEqualTo(409);
        // A rejection does not block a later draft for the same problem
        assertThat(body(send("POST", "/api/incidents/drafts", "{\"rank\":3}")).get("created").asBoolean()).isTrue();
    }

    @Test
    void refusesDraftsThatShouldNotBeMade() throws Exception {
        HttpResponse<String> propagated = send("POST", "/api/incidents/drafts", "{\"rank\":5}");
        assertThat(propagated.statusCode()).isEqualTo(400);
        assertThat(propagated.body()).contains("only repeats a failure that started elsewhere")
                .contains("finding 4 (LedgerPostingException in deposit-p1)");

        assertThat(send("POST", "/api/incidents/drafts", "{\"rank\":99}").body()).contains("'rank' must be between 1 and 8");
        assertThat(send("POST", "/api/incidents/drafts", "{}").statusCode()).isEqualTo(400);
        assertThat(send("GET", "/api/incidents/DRAFT-nope", null).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/api/incidents/DRAFT-nope/approve", "{}").statusCode()).isEqualTo(404);
    }

    @Test
    void aNoteMayOnlyMentionWhatTheFindingContains() throws Exception {
        HttpResponse<String> invented = send("POST", "/api/incidents/drafts",
                "{\"rank\":4,\"note\":\"Caused by commit deadbeef99 in Other.java, see RB-777\"}");
        assertThat(invented.statusCode()).isEqualTo(400);
        assertThat(invented.body()).contains("'note' cites").contains("deadbeef99").contains("Other.java").contains("RB-777");
        assertThat(send("POST", "/api/incidents/drafts", "{\"rank\":4,\"note\":\"" + "x".repeat(601) + "\"}").body())
                .contains("too long");

        JsonNode ok = body(send("POST", "/api/incidents/drafts",
                "{\"rank\":4,\"note\":\"LedgerPostingException matches runbook RB-002; card 4111111111111111 was affected.\"}"));
        assertThat(ok.get("created").asBoolean()).isTrue();
        // Kept, masked, and labelled in the description
        assertThat(ok.get("draft").get("note").asString())
                .isEqualTo("LedgerPostingException matches runbook RB-002; card ************1111 was affected.");
        assertThat(ok.get("draft").get("description").asString()).contains("Note added when drafting (AI-assisted): Ledger");
        assertThat(ok.get("draft").get("assignmentGroup").asString()).isEqualTo("Deposits Engineering");
        assertThat(ok.get("draft").get("priority").asString()).isEqualTo("3 - Moderate");
    }

    @Test
    void theAssistantCanDraftButHasNoWayToApprove() throws Exception {
        JsonNode tools = body(send("GET", "/api/tools", null));
        assertThat(tools).extracting(t -> t.get("name").asString()).contains("draftIncident")
                .noneMatch(name -> name.toLowerCase().contains("approve") || name.toLowerCase().contains("submit"));

        JsonNode answer = body(send("POST", "/api/assistant/ask", "{\"question\":\"Please raise an incident for finding 7\"}"));

        assertThat(answer.get("toolCalls").toString()).isEqualTo("[{\"tool\":\"draftIncident\",\"arguments\":\"{\\\"rank\\\":7}\"}]");
        assertThat(answer.get("answer").asString()).startsWith("Incident draft DRAFT-")
                .contains("InsufficientCassetteException in withdrawal-p1").contains("Priority 4 - Low")
                .contains("assigned to Field Services")
                .endsWith("Nothing has been sent: it is waiting for a person to approve it.");
        // The draft the tool made is an ordinary draft, waiting
        JsonNode drafts = body(send("GET", "/api/incidents?limit=100", null));
        assertThat(drafts).anyMatch(d -> d.get("findingRank").asInt() == 7 && d.get("status").asString().equals("DRAFT"));

        // A finding without a runbook owner goes to the default group
        JsonNode ui = body(send("POST", "/api/tools/draftIncident", "{\"rank\":8}"));
        assertThat(ui.get("draft").get("assignmentGroup").asString()).isIn("Core Banking Integration", "Production Support");
    }

    private static String mockFile() throws Exception {
        Path file = Path.of("build/test-data/incidents/workflow-test.jsonl");
        return Files.exists(file) ? Files.readString(file) : "";
    }
}
