package com.srividhya.bankrca.change;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param mode stub (a JSON file) or servicenow (the change_request table; uses the connection under rca.incident.servicenow)
 * @param stubFile the editable sample changes
 * @param ciNames component name in the logs to configuration item name in the change system, for
 *        those that differ; a component not listed is looked up under its own name
 */
@ConfigurationProperties("rca.change")
public record ChangeProps(String mode, String stubFile, Map<String, String> ciNames) {

    public String ciName(String component) {
        return ciNames == null ? component : ciNames.getOrDefault(component, component);
    }
}
