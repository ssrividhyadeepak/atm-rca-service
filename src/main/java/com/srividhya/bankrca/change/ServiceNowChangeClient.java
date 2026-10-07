package com.srividhya.bankrca.change;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import com.srividhya.bankrca.incident.IncidentProps;
import com.srividhya.bankrca.servicenow.ServiceNowConnection;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real implementation: reads change requests from ServiceNow's change_request table
 * through the Table API - one GET, nothing is written. Used when rca.change.mode=servicenow.
 *
 * A change is matched to a component by its configuration item's name (cmdb_ci.name), which
 * is the component name unless rca.change.ci-names says otherwise.
 */
@Component
@ConditionalOnProperty(name = "rca.change.mode", havingValue = "servicenow")
public class ServiceNowChangeClient implements ChangeClient {

    private static final String TABLE = "/api/now/table/change_request";
    private static final String FIELDS = "number,short_description,description,type,state,risk,cmdb_ci,assignment_group,"
            + "start_date,end_date,work_start,work_end,close_code";
    // The Table API takes and returns times in UTC in this form
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ServiceNowConnection connection;
    private final ChangeProps props;
    private final JsonMapper json = new JsonMapper();

    public ServiceNowChangeClient(IncidentProps incident, ChangeProps props) {
        this.connection = ServiceNowConnection.open(incident.servicenow(), "rca.change.mode is servicenow");
        this.props = props;
    }

    @Override
    public String description() {
        return "ServiceNow " + connection.instance() + " change_request table (read-only)";
    }

    @Override
    public List<ChangeRequest> changes(List<String> components, Instant from, Instant to, int limit) {
        Map<String, String> componentByCi = new LinkedHashMap<>();
        for (String component : components) {
            componentByCi.put(props.ciName(component), component);
        }
        // Planned to overlap the window; ^ is "and" in an encoded query
        String query = "cmdb_ci.nameIN" + String.join(",", componentByCi.keySet())
                + "^start_date<=" + TIME.format(to.atOffset(ZoneOffset.UTC))
                + "^end_date>=" + TIME.format(from.atOffset(ZoneOffset.UTC))
                + "^ORDERBYDESCstart_date";
        try {
            String body = connection.rest().get().uri(b -> b.path(TABLE).queryParam("sysparm_query", "{query}")
                    .queryParam("sysparm_fields", FIELDS)
                    // Both the stored value (for times) and the display value (for names)
                    .queryParam("sysparm_display_value", "all")
                    .queryParam("sysparm_limit", limit).build(query))
                    .retrieve().body(String.class);
            JsonNode result = json.readTree(body).path("result");
            List<ChangeRequest> changes = new ArrayList<>();
            for (JsonNode row : result) {
                String service = display(row, "cmdb_ci");
                changes.add(new ChangeRequest(display(row, "number"), componentByCi.getOrDefault(service, service), service,
                        display(row, "type"), display(row, "state"), display(row, "risk"), display(row, "short_description"),
                        display(row, "description"), display(row, "assignment_group"),
                        // When the work was actually done, if it was recorded
                        time(row, "work_start", "start_date"), time(row, "work_end", "end_date"),
                        display(row, "close_code")));
            }
            return changes;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            throw new IllegalStateException(status == 401 ? "ServiceNow rejected the credentials (401)"
                    : status == 403 ? "The ServiceNow account may not read change requests (403)"
                            : "ServiceNow answered with status " + status);
        } catch (ResourceAccessException e) {
            throw new IllegalStateException("Could not reach ServiceNow at " + connection.instance() + " ("
                    + e.getMostSpecificCause().getClass().getSimpleName() + ")");
        } catch (JacksonException e) {
            throw new IllegalStateException("ServiceNow did not answer with JSON");
        }
    }

    /** With sysparm_display_value=all each field is {"value": ..., "display_value": ...}. */
    private static String display(JsonNode row, String field) {
        JsonNode f = row.path(field);
        String text = f.isObject() ? f.path("display_value").asString("") : f.asString("");
        return text.isBlank() ? null : text;
    }

    private static String time(JsonNode row, String... fields) {
        for (String field : fields) {
            JsonNode f = row.path(field);
            String value = f.isObject() ? f.path("value").asString("") : f.asString("");
            if (!value.isBlank()) {
                try {
                    return LocalDateTime.parse(value, TIME).toInstant(ZoneOffset.UTC).toString();
                } catch (DateTimeParseException e) {
                    // try the next field
                }
            }
        }
        return null;
    }
}
