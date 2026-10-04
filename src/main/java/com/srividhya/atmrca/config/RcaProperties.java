package com.srividhya.atmrca.config;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rca")
public record RcaProperties(String storage, Mongo mongo, Monitor monitor, Splunk splunk, Stub stub) {

    public record Mongo(String uri, String database) {

        /** The URI contains credentials: never print it, whoever logs this record. */
        @Override
        public String toString() {
            return "Mongo[database=" + database + ", uri=<redacted>]";
        }
    }

    /**
     * @param runOnStartup run once as soon as the service is up, so a wrong Splunk setting shows immediately
     * @param window how far back each run looks
     * @param transactions the transaction names whose failures are monitored
     */
    public record Monitor(boolean enabled, String cron, boolean runOnStartup, Duration window,
            List<String> transactions) {
    }

    /**
     * @param mode stub (synthetic events) or live
     * @param baseUrl Splunk management URL (port 8089 by default), not the web UI address
     * @param searches SPL template per named search
     */
    public record Splunk(String mode, String baseUrl, String token, String username, String password, String index,
            Duration timeout, int maxRows, Map<String, String> searches) {

        /** Never prints the credentials, whoever logs this record. */
        @Override
        public String toString() {
            return "Splunk[mode=" + mode + ", baseUrl=" + baseUrl + ", index=" + index + ", credentials=<redacted>]";
        }
    }

    /** anchor: a fixed "now" so the synthetic day is reproducible in tests; null means the real clock. */
    public record Stub(Instant anchor) {
    }
}
