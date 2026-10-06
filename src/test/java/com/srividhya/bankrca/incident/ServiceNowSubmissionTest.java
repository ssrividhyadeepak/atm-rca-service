package com.srividhya.bankrca.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.srividhya.bankrca.FakeServiceNow;
import com.srividhya.bankrca.incident.IncidentProps.ServiceNow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real ticket-system client, with a stand-in for ServiceNow's incident API: what is sent
 * on approval, what happens when ServiceNow is down, and that a retry cannot create a duplicate.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "rca.incident.mode=servicenow")
@ActiveProfiles("test")
class ServiceNowSubmissionTest {

    private static final FakeServiceNow SERVICENOW = new FakeServiceNow();

    @DynamicPropertySource
    static void servicenow(DynamicPropertyRegistry registry) {
        registry.add("rca.incident.servicenow.instance-url", SERVICENOW::url);
        registry.add("rca.incident.servicenow.username", () -> "svc-rca");
        registry.add("rca.incident.servicenow.password", () -> "s3cret");
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = new JsonMapper();

    private JsonNode send(String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        return json.readTree(http.send(b.build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    @Test
    void sendsTheApprovedIncidentAndSurvivesAnOutage() throws Exception {
        // The stand-in is shared by the tests of this class: count from where it stands now
        int requestsBefore = SERVICENOW.requests.size();
        int incidentsBefore = SERVICENOW.incidents.size();
        send("/api/rca", null);
        String id = send("/api/incidents/drafts", "{\"rank\":1}").get("draft").get("id").asString();
        assertThat(SERVICENOW.requests).as("a draft sends nothing").hasSize(requestsBefore);

        // ServiceNow is down when the draft is approved
        SERVICENOW.createStatus = 503;
        JsonNode approved = send("/api/incidents/" + id + "/approve", "{}");
        assertThat(approved.get("status").asString()).as("the approval is kept").isEqualTo("APPROVED");
        assertThat(approved.get("lastError").asString()).isEqualTo("ServiceNow answered with status 503");
        assertThat(approved.get("incidentNumber").isNull()).isTrue();
        assertThat(SERVICENOW.incidents).hasSize(incidentsBefore);

        // It comes back; the submission is retried without a second approval
        SERVICENOW.createStatus = 201;
        JsonNode submitted = send("/api/incidents/" + id + "/submit", null);
        assertThat(submitted.get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(submitted.get("incidentNumber").asString()).matches("INC00\\d{5}");
        assertThat(submitted.get("incidentSystem").asString()).isEqualTo("127.0.0.1");
        assertThat(submitted.get("lastError").isNull()).isTrue();

        assertThat(SERVICENOW.authorization).isEqualTo("Basic c3ZjLXJjYTpzM2NyZXQ=");
        assertThat(SERVICENOW.incidents).as("created once, despite two attempts").hasSize(incidentsBefore + 1);
        JsonNode sent = SERVICENOW.incidents.get(incidentsBefore);
        assertThat(sent.get("short_description").asString())
                .isEqualTo("[RCA] HostAuthTimeoutException in withdrawal-p1: 64 failures (BURST)");
        assertThat(sent.get("correlation_id").asString()).isEqualTo(id);
        assertThat(sent.get("cmdb_ci").asString()).isEqualTo("withdrawal-p1");
        assertThat(sent.get("assignment_group").asString()).isEqualTo("Bank Platform Engineering");
        // "2 - High"
        assertThat(sent.get("impact").asString()).isEqualTo("2");
        assertThat(sent.get("urgency").asString()).isEqualTo("2");
        assertThat(sent.get("description").asString()).contains("Likely cause").contains("Reviewed and approved by a person");
        // Looked for by correlation id before each create; names are sent, not sys_ids
        assertThat(SERVICENOW.requests.get(requestsBefore)).startsWith("GET ").contains("correlation_id=" + id);
        assertThat(SERVICENOW.requests.get(SERVICENOW.requests.size() - 1)).startsWith("POST ")
                .contains("sysparm_input_display_value=true");
    }

    @Test
    void aRetryFindsTheIncidentThatWasAlreadyCreated() {
        // As after a timeout: ServiceNow created it, the answer never arrived
        ServiceNowIncidentClient client = new ServiceNowIncidentClient(props(SERVICENOW.url(), "svc-rca", "s3cret", null));
        IncidentDraft draft = new IncidentDraft("DRAFT-retry001", "APPROVED", "2026-10-02", "sig", 1, "short", "long",
                "3 - Moderate", "deposit-p1", "Deposits Engineering", "CODE_DEFECT", null, null, null, "copilot",
                "2026-10-02T00:00:00Z", "oncall", "2026-10-02T00:01:00Z", null, null, null, null, null);
        int before = SERVICENOW.incidents.size();

        String first = client.create(draft).number();
        String second = client.create(draft).number();

        assertThat(second).isEqualTo(first);
        assertThat(SERVICENOW.incidents).as("created once").hasSize(before + 1);
    }

    @Test
    void usesABearerTokenWhenOneIsSetAndExplainsFailures() {
        IncidentDraft draft = new IncidentDraft("DRAFT-auth0001", "APPROVED", "2026-10-02", "sig2", 1, "short", "long",
                "4 - Low", "ui-base-p1", "Channels UI", "CLIENT_UI", null, null, null, "copilot", "2026-10-02T00:00:00Z",
                "oncall", "2026-10-02T00:01:00Z", null, null, null, null, null);
        new ServiceNowIncidentClient(props(SERVICENOW.url() + "/", null, null, "tok-123")).create(draft);
        assertThat(SERVICENOW.authorization).isEqualTo("Bearer tok-123");

        FakeServiceNow down = new FakeServiceNow();
        ServiceNowIncidentClient client = new ServiceNowIncidentClient(props(down.url(), null, null, "tok-123"));
        down.createStatus = 401;
        IncidentDraft other = new IncidentDraft("DRAFT-auth0002", "APPROVED", "2026-10-02", "sig3", 1, "s", "l", "4 - Low",
                "c", "g", "x", null, null, null, "a", "2026-10-02T00:00:00Z", "b", "2026-10-02T00:01:00Z", null, null, null,
                null, null);
        assertThatThrownBy(() -> client.create(other)).hasMessage("ServiceNow rejected the credentials (401)");
        down.createStatus = 403;
        assertThatThrownBy(() -> client.create(other)).hasMessage("The ServiceNow account may not create incidents (403)");
        down.stop();
        assertThatThrownBy(() -> client.create(other)).hasMessageContaining("Could not reach ServiceNow at http://127.0.0.1:")
                .message().doesNotContain("tok-123");
    }

    @Test
    void refusesToStartWithoutUrlCredentialsOrHttps() {
        assertThatThrownBy(() -> new ServiceNowIncidentClient(props(" ", "u", "p", null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SERVICENOW_URL is not set");
        assertThatThrownBy(() -> new ServiceNowIncidentClient(props(SERVICENOW.url(), "u", null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no credentials are set");
        assertThatThrownBy(() -> new ServiceNowIncidentClient(props("http://yourbank.service-now.com", "u", "p", null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("must use https");
        assertThat(props(SERVICENOW.url(), "u", "s3cret", "tok-123").servicenow().toString()).doesNotContain("s3cret")
                .doesNotContain("tok-123");
    }

    private static IncidentProps props(String url, String username, String password, String token) {
        return new IncidentProps("servicenow", null, Map.of(), "Production Support", true, Duration.ofDays(7),
                new ServiceNow(url, username, password, token, Duration.ofSeconds(5)));
    }
}
