package com.srividhya.bankrca.failure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.srividhya.bankrca.failure.RawEventParser.Parsed;

/** Events in the shape the container platform writes them; every name and id here is made up. */
class RawEventParserTest {

    private final RawEventParser parser = new RawEventParser();

    private static String event(String container, String level, String message) {
        return "{\"@timestamp\":\"2026-10-05T01:03:46.716672092Z\",\"hostname\":\"ocp-node-12.example.net\","
                + "\"kubernetes\":{\"container_name\":\"" + container + "\",\"namespace_name\":\"prod\","
                + "\"pod_name\":\"" + container + "-deploy-84b5f8f6c-8j4q8\"},\"level\":\"" + level + "\","
                + "\"log_source\":\"container\",\"log_type\":\"application\",\"message\":\"" + message + "\","
                + "\"openshift\":{\"labels\":{\"clustername\":\"east1\",\"datacenter\":\"dc1\"}},"
                + "\"timestamp\":\"2026-10-05T01:03:46.716672209Z\"}";
    }

    @Test
    void extractsComponentPodAndTracingMetadata() {
        Parsed p = parser.parse(event("token-info-p1", "info",
                "2026-10-04T18:03:46,716-07:00 -- LEVEL: INFO com.example.bank.token.component.InvokeWithCircuitBreaker "
                        + "928202595 -[http-nio-8080-exec-6] --Q1231-80cc944e-7925-7d33-3799-2694c2a6898a- "
                        + "In downstreamListCardPaymentFallback with Exception..."),
                "2026-10-05T01:03:47Z");

        assertThat(p.timestamp()).isEqualTo("2026-10-05T01:03:46.716672092Z");
        assertThat(p.component()).isEqualTo("token-info-p1");
        assertThat(p.pod()).isEqualTo("token-info-p1-deploy-84b5f8f6c-8j4q8");
        assertThat(p.namespace()).isEqualTo("prod");
        assertThat(p.cluster()).isEqualTo("east1");
        assertThat(p.host()).isEqualTo("ocp-node-12.example.net");
        assertThat(p.level()).isEqualTo("INFO");
        assertThat(p.logger()).isEqualTo("com.example.bank.token.component.InvokeWithCircuitBreaker");
        assertThat(p.thread()).isEqualTo("http-nio-8080-exec-6");
        assertThat(p.bankId()).isEqualTo("Q1231");
        assertThat(p.traceId()).isEqualTo("80cc944e-7925-7d33-3799-2694c2a6898a");
        assertThat(p.sessionId()).isNull();
        // The word "Exception" alone is not an exception class
        assertThat(p.exception()).isNull();
        assertThat(p.stackTrace()).isNull();
        assertThat(p.message()).isEqualTo("In downstreamListCardPaymentFallback with Exception...");
        assertThat(p.structured()).isTrue();
    }

    @Test
    void separatesTheExceptionAndItsStackTraceFromTheMessage() {
        // Frames separated by U+2028, as some log shippers write a line break
        Parsed p = parser.parse(event("customer-profile-p1", "error",
                "2026-10-04T18:03:44,921-07:00 -- LEVEL: ERROR com.example.bank.infrastructure.web.CoreRestErrorHandler "
                        + "928111695 -[http-nio-8080-exec-26] --Q0457-0e965c5d-52db-bcd0-73f0-4f60bff9071a- attached exception: "
                        + "com.example.jsk.core.exceptions.BadResponseException(ResponseMapper): Mandatory field missing "
                        + "from the response, lastMaintainedUserId\\u2028\\tat com.example.jsk.clients.ResponseMapper."
                        + "convert(ResponseMapper.java:340)\\u2028\\tat com.example.bank.profile.ProfileController."
                        + "getSetup(ProfileController.java:211)\\u2028\\tat org.springframework.web.servlet."
                        + "FrameworkServlet.service(FrameworkServlet.java:885)"),
                null);

        assertThat(p.level()).isEqualTo("ERROR");
        assertThat(p.bankId()).isEqualTo("Q0457");
        assertThat(p.traceId()).isEqualTo("0e965c5d-52db-bcd0-73f0-4f60bff9071a");
        assertThat(p.exception()).isEqualTo("com.example.jsk.core.exceptions.BadResponseException");
        assertThat(p.message()).isEqualTo("attached exception: com.example.jsk.core.exceptions.BadResponseException"
                + "(ResponseMapper): Mandatory field missing from the response, lastMaintainedUserId");
        assertThat(p.stackTrace().lines()).hasSize(4);
        assertThat(p.stackTrace().lines().toList().get(1))
                .isEqualTo("\tat com.example.jsk.clients.ResponseMapper.convert(ResponseMapper.java:340)");
        assertThat(p.stackTrace()).doesNotContain(" ");
    }

    @Test
    void readsTracingMetadataFromAUiEvent() {
        Parsed p = parser.parse(event("ui-base-p1", "info",
                "2026-10-04T18:03:48,294-07:00 -- LEVEL: INFO com.example.bank.ui.api.controller.BankUiController "
                        + "328242080 -[http-nio-8080-exec-13] UI MOD BANK ID:Q0457 Timestamp: 10/04/2026 18:03:43.940 PT "
                        + "CustomerTrackingSessionId:62C8E0B533BA63C6E6B51DF90CA7150 "
                        + "\\\"CustomerCommSetup: Exception - undefined\\\""),
                null);

        assertThat(p.component()).isEqualTo("ui-base-p1");
        assertThat(p.bankId()).isEqualTo("Q0457");
        assertThat(p.traceId()).isNull();
        assertThat(p.sessionId()).isEqualTo("62C8E0B533BA63C6E6B51DF90CA7150");
        assertThat(p.exception()).isNull();
        assertThat(p.message()).endsWith("\"CustomerCommSetup: Exception - undefined\"");
    }

    @Test
    void theIdLabelOnUiEventsIsConfigurable() {
        String message = "2026-10-04T18:03:48,294-07:00 -- LEVEL: INFO com.example.bank.ui.KioskController 1 -[exec-1] "
                + "UI MOD KIOSK NO:K77 Timestamp: x";

        assertThat(new RawEventParser("KIOSK NO").parse(event("ui-base-p1", "info", message), null).bankId())
                .isEqualTo("K77");
        assertThat(parser.parse(event("ui-base-p1", "info", message), null).bankId()).isNull();
    }

    @Test
    void keepsWhatItCanFromEventsInAnotherFormat() {
        // JSON from the platform, but the application line has no "-- LEVEL:" prefix
        Parsed json = parser.parse(event("batch-p1", "error", "job failed: java.lang.IllegalStateException: boom"),
                null);
        assertThat(json.component()).isEqualTo("batch-p1");
        assertThat(json.level()).isEqualTo("ERROR");
        assertThat(json.exception()).isEqualTo("java.lang.IllegalStateException");
        assertThat(json.logger()).isNull();
        assertThat(json.structured()).isFalse();

        // Not JSON at all
        Parsed plain = parser.parse("ERROR something broke: java.net.SocketTimeoutException: Read timed out",
                "2026-10-05T01:00:00Z");
        assertThat(plain.timestamp()).isEqualTo("2026-10-05T01:00:00Z");
        assertThat(plain.component()).isNull();
        assertThat(plain.exception()).isEqualTo("java.net.SocketTimeoutException");
        assertThat(plain.structured()).isFalse();

        assertThat(parser.parse(null, null).message()).isNull();
    }
}
