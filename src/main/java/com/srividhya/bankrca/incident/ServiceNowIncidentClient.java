package com.srividhya.bankrca.incident;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.srividhya.bankrca.incident.IncidentProps.ServiceNow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real implementation: creates the incident in ServiceNow through its Table API. Used
 * only when rca.incident.mode=servicenow is set explicitly - creating real tickets is not
 * something the prod profile turns on by itself.
 *
 * The draft id is sent as correlation_id, and the client looks for an incident with that
 * correlation_id before creating one, so a retry after a timeout does not make a duplicate.
 */
@Component
@ConditionalOnProperty(name = "rca.incident.mode", havingValue = "servicenow")
public class ServiceNowIncidentClient implements IncidentClient {

    private static final Logger log = LoggerFactory.getLogger(ServiceNowIncidentClient.class);
    private static final String TABLE = "/api/now/table/incident";

    private final RestClient rest;
    private final URI instance;
    private final JsonMapper json = new JsonMapper();

    public ServiceNowIncidentClient(IncidentProps props) {
        ServiceNow sn = props.servicenow();
        if (sn == null || sn.instanceUrl() == null || sn.instanceUrl().isBlank()) {
            throw new IllegalStateException("rca.incident.mode is servicenow but SERVICENOW_URL is not set, "
                    + "e.g. https://yourbank.service-now.com");
        }
        this.instance = URI.create(sn.instanceUrl().strip().replaceAll("/+$", ""));
        boolean local = "localhost".equals(instance.getHost()) || "127.0.0.1".equals(instance.getHost());
        if (!"https".equals(instance.getScheme()) && !local) {
            throw new IllegalStateException("SERVICENOW_URL must use https: credentials are sent with every call");
        }
        String authorization;
        if (hasText(sn.token())) {
            authorization = "Bearer " + sn.token().strip();
        } else if (hasText(sn.username()) && hasText(sn.password())) {
            authorization = "Basic " + Base64.getEncoder()
                    .encodeToString((sn.username() + ":" + sn.password()).getBytes(StandardCharsets.UTF_8));
        } else {
            throw new IllegalStateException("rca.incident.mode is servicenow but no credentials are set. Set "
                    + "SERVICENOW_USERNAME and SERVICENOW_PASSWORD, or SERVICENOW_TOKEN");
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(sn.timeout() == null ? Duration.ofSeconds(30) : sn.timeout());
        this.rest = RestClient.builder().baseUrl(instance.toString()).requestFactory(factory)
                .defaultHeader("Authorization", authorization).defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String description() {
        return "ServiceNow " + instance + " (real incidents are created on approval)";
    }

    @Override
    public Created create(IncidentDraft draft) {
        try {
            // Already there from an earlier attempt?
            String found = rest.get().uri(b -> b.path(TABLE).queryParam("sysparm_query", "correlation_id=" + draft.id())
                    .queryParam("sysparm_fields", "number").queryParam("sysparm_limit", "1").build())
                    .retrieve().body(String.class);
            JsonNode existing = json.readTree(found).path("result");
            if (existing.isArray() && existing.size() > 0) {
                log.info("Draft {} is already in ServiceNow as {}", draft.id(), existing.get(0).get("number").asString());
                return new Created(existing.get(0).get("number").asString(), instance.getHost());
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("short_description", draft.shortDescription());
            body.put("description", draft.description());
            body.put("correlation_id", draft.id());
            body.put("correlation_display", "bank-rca-service");
            body.put("cmdb_ci", draft.component());
            body.put("assignment_group", draft.assignmentGroup());
            body.put("category", "software");
            // "2 - High" -> impact 2, urgency 2: ServiceNow derives the priority from these
            String level = draft.priority() == null || draft.priority().isBlank() ? "3" : draft.priority().substring(0, 1);
            body.put("impact", level);
            body.put("urgency", level);
            // Names, not sys_ids, for the group and configuration item
            String created = rest.post().uri(b -> b.path(TABLE).queryParam("sysparm_input_display_value", "true")
                    .queryParam("sysparm_fields", "number,sys_id").build())
                    .contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body)).retrieve()
                    .body(String.class);
            String number = json.readTree(created).path("result").path("number").asString("");
            if (number.isBlank()) {
                throw new IllegalStateException("ServiceNow accepted the request but returned no incident number");
            }
            return new Created(number, instance.getHost());
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            throw new IllegalStateException(status == 401 ? "ServiceNow rejected the credentials (401)"
                    : status == 403 ? "The ServiceNow account may not create incidents (403)"
                            : "ServiceNow answered with status " + status);
        } catch (ResourceAccessException e) {
            throw new IllegalStateException("Could not reach ServiceNow at " + instance + " ("
                    + e.getMostSpecificCause().getClass().getSimpleName() + ")");
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
