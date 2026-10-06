package com.srividhya.bankrca.incident;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param mode mock (nothing is sent) or servicenow
 * @param mockFile where the mock records what it would have created
 * @param priority RCA severity (HIGH, MEDIUM, LOW) to the ticket system's priority
 * @param defaultAssignmentGroup used when the finding's runbook names no owner
 * @param fourEyes with security on, the person who approves must not be the client that drafted
 * @param dedupeWindow a signature that already has an open or submitted incident newer than this gets no second one
 */
@ConfigurationProperties("rca.incident")
public record IncidentProps(String mode, Path mockFile, Map<String, String> priority, String defaultAssignmentGroup,
        boolean fourEyes, Duration dedupeWindow, ServiceNow servicenow) {

    /**
     * @param instanceUrl e.g. https://yourbank.service-now.com
     * @param token an OAuth bearer token; used when set, otherwise username and password
     */
    public record ServiceNow(String instanceUrl, String username, String password, String token, Duration timeout) {

        /** Never prints the credentials, whoever logs this record. */
        @Override
        public String toString() {
            return "ServiceNow[instanceUrl=" + instanceUrl + ", credentials=<redacted>]";
        }
    }
}
