package com.srividhya.bankrca.change;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.srividhya.bankrca.FakeServiceNow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real change-request client, with a stand-in for ServiceNow's change_request table:
 * what is asked for, how the answer is read, and what happens when ServiceNow says no.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = { "rca.change.mode=servicenow",
        "rca.change.ci-names.withdrawal-p1=Withdrawal Service" })
@ActiveProfiles("test")
class ServiceNowChangeTest {

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

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String field(String name, String value, String display) {
        return "\"" + name + "\":{\"value\":\"" + value + "\",\"display_value\":\"" + display + "\"}";
    }

    @Test
    void readsChangeRequestsFromTheTableApi() throws Exception {
        SERVICENOW.changeStatus = 200;
        SERVICENOW.changes = "{\"result\":[{" + field("number", "CHG0041001", "CHG0041001") + ","
                + field("short_description", "Lower host timeout", "Lower host timeout") + ","
                + field("description", "Owner a.b@example.com", "Owner a.b@example.com") + ","
                + field("type", "standard", "Standard") + "," + field("state", "3", "Closed") + ","
                + field("risk", "4", "Low") + "," + field("cmdb_ci", "9f3c", "Withdrawal Service") + ","
                + field("assignment_group", "77aa", "Card Platform") + ","
                + field("start_date", "2026-10-01 20:00:00", "01/10/2026 21:00:00") + ","
                + field("end_date", "2026-10-01 21:00:00", "01/10/2026 22:00:00") + ","
                + field("work_start", "2026-10-01 20:05:00", "x") + "," + field("work_end", "", "") + ","
                + field("close_code", "successful", "Successful") + "}]}";

        JsonNode found = json.readTree(get("/api/changes?component=withdrawal-p1&hours=48").body());

        JsonNode change = found.get("changes").get(0).get("change");
        assertThat(change.get("number").asString()).isEqualTo("CHG0041001");
        // Display values for names; the configuration item is mapped back to the component
        assertThat(change.get("service").asString()).isEqualTo("Withdrawal Service");
        assertThat(change.get("component").asString()).isEqualTo("withdrawal-p1");
        assertThat(change.get("state").asString()).isEqualTo("Closed");
        assertThat(change.get("assignmentGroup").asString()).isEqualTo("Card Platform");
        // Stored values for times: the actual start when recorded, otherwise the planned one
        assertThat(change.get("startedAt").asString()).isEqualTo("2026-10-01T20:05:00Z");
        assertThat(change.get("endedAt").asString()).isEqualTo("2026-10-01T21:00:00Z");
        assertThat(change.get("description").asString()).doesNotContain("a.b@example.com");
        assertThat(found.get("source").asString()).contains("change_request table (read-only)");

        String request = SERVICENOW.requests.get(SERVICENOW.requests.size() - 1);
        assertThat(request).startsWith("GET change_request ")
                .contains("sysparm_query=cmdb_ci.nameINWithdrawal Service^start_date<=").contains("^end_date>=")
                .contains("sysparm_display_value=all").contains("sysparm_limit=50");
        assertThat(SERVICENOW.authorization).startsWith("Basic ");
    }

    @Test
    void aRefusalIsReportedWithoutDetails() throws Exception {
        SERVICENOW.changeStatus = 403;

        HttpResponse<String> response = get("/api/changes?component=withdrawal-p1");

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).contains("may not read change requests (403)").doesNotContain("s3cret");
    }
}
