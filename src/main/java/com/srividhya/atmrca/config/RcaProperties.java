package com.srividhya.atmrca.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rca")
public record RcaProperties(String storage, Mongo mongo, Monitor monitor) {

    public record Mongo(String uri, String database) {

        /** The URI contains credentials: never print it, whoever logs this record. */
        @Override
        public String toString() {
            return "Mongo[database=" + database + ", uri=<redacted>]";
        }
    }

    public record Monitor(boolean enabled, String cron) {
    }
}
