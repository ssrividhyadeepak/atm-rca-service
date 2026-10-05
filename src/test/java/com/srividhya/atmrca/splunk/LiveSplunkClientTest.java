package com.srividhya.atmrca.splunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.srividhya.atmrca.FakeSplunk;
import com.srividhya.atmrca.config.RcaProperties;
import com.srividhya.atmrca.config.RcaProperties.Splunk;

/** The live Splunk client against a stand-in for Splunk's REST API. */
class LiveSplunkClientTest {

    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-02T00:00:00Z");
    private static final String SPL = "search index=$index$ \"*exception*\" transaction IN ($transactions$) | head $limit$";
    private static final Map<String, Object> ARGS = Map.of("transactions", List.of("cash-withdrawal", "cash-deposit"),
            "limit", 500);

    private final FakeSplunk splunk = new FakeSplunk();

    @AfterEach
    void stop() {
        splunk.stop();
    }

    @Test
    void sendsTheConfiguredSearchWithTokenWindowAndRenderedList() {
        client("tok-123", null, null).search(SplunkClient.FAILED_TRANSACTIONS, FROM, TO, ARGS);

        assertThat(splunk.path).isEqualTo("POST /services/search/jobs");
        assertThat(splunk.authorization).isEqualTo("Bearer tok-123");
        assertThat(splunk.form)
                .containsEntry("search", "search index=atm_app \"*exception*\" transaction IN "
                        + "(\"cash-withdrawal\",\"cash-deposit\") | head 500")
                .containsEntry("earliest_time", "1790812800.000").containsEntry("latest_time", "1790899200.000")
                .containsEntry("exec_mode", "oneshot").containsEntry("output_mode", "json").containsEntry("count", "1000");
    }

    @Test
    void usesBasicAuthWhenThereIsNoToken() {
        client(null, "svc-rca", "s3cret").search(SplunkClient.FAILED_TRANSACTIONS, FROM, TO, ARGS);

        assertThat(splunk.authorization).isEqualTo("Basic c3ZjLXJjYTpzM2NyZXQ=");
    }

    @Test
    void turnsSplunkRowsIntoPlainValues() {
        splunk.response = """
                {"results":[{"ts":"1790860412.337","service":"withdrawal-service","transaction":"cash-withdrawal",
                 "correlationId":"MSG-a1","exception":"com.example.HostAuthTimeoutException","message":"timed out",
                 "stackTrace":["com.example.HostAuthTimeoutException: timed out","\\tat com.example.A.b(A.java:1)"]}]}""";

        List<Map<String, Object>> rows = client("t", null, null).search(SplunkClient.FAILED_TRANSACTIONS, FROM, TO, ARGS);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("ts", "2026-10-01T13:13:32.337Z").containsEntry("correlationId", "MSG-a1")
                .containsEntry("stackTrace", "com.example.HostAuthTimeoutException: timed out\n\tat com.example.A.b(A.java:1)");
    }

    @Test
    void refusesArgumentsThatCouldChangeTheSearch() {
        LiveSplunkClient client = client("t", null, null);

        for (String bad : List.of("x\" | delete", "a | outputlookup x", "[search index=*]", "a`b", "a) OR (b", "")) {
            assertThatThrownBy(() -> client.search(SplunkClient.FAILED_TRANSACTIONS, FROM, TO,
                    Map.of("transactions", List.of("cash-withdrawal", bad), "limit", 10)))
                    .as(bad).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not allowed");
        }
        assertThatThrownBy(() -> client.search(SplunkClient.FAILED_TRANSACTIONS, FROM, TO,
                Map.of("transactions", List.of(), "limit", 10))).hasMessageContaining("is empty");
        assertThatThrownBy(() -> client.search("search index=* | delete", FROM, TO, Map.of()))
                .hasMessageContaining("Unknown search");
        assertThat(splunk.path).as("nothing was sent to Splunk").isNull();
    }

    @Test
    void explainsFailuresWithoutEchoingCredentials() {
        splunk.status = 401;
        assertThatThrownBy(() -> client("tok-123", null, null).search(SplunkClient.FAILED_TRANSACTIONS, FROM, TO, ARGS))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("rejected the credentials (401)")
                .message().doesNotContain("tok-123");
        splunk.status = 403;
        assertThatThrownBy(() -> client("tok-123", null, null).search(SplunkClient.FAILED_TRANSACTIONS, FROM, TO, ARGS))
                .hasMessageContaining("not allowed to run this search (403)");

        splunk.stop();
        assertThatThrownBy(() -> client("tok-123", null, null).search(SplunkClient.FAILED_TRANSACTIONS, FROM, TO, ARGS))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Could not reach Splunk at http://127.0.0.1:")
                .message().doesNotContain("tok-123");
    }

    @Test
    void refusesToStartWithoutUrlCredentialsOrHttps() {
        assertThatThrownBy(() -> new LiveSplunkClient(props(" ", "t", null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SPLUNK_URL is not set");
        assertThatThrownBy(() -> new LiveSplunkClient(props(splunk.url(), null, "user", null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no Splunk credentials");
        assertThatThrownBy(() -> new LiveSplunkClient(props("http://splunk.example.com:8089", "t", null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("must use https");
        assertThat(props(splunk.url(), "tok-123", "user", "s3cret").toString()).doesNotContain("tok-123")
                .doesNotContain("s3cret");
    }

    private LiveSplunkClient client(String token, String username, String password) {
        return new LiveSplunkClient(props(splunk.url() + "/", token, username, password));
    }

    private static RcaProperties props(String url, String token, String username, String password) {
        Splunk splunk = new Splunk("live", url, token, username, password, "atm_app", Duration.ofSeconds(5), 1000,
                Map.of(SplunkClient.FAILED_TRANSACTIONS, SPL));
        return new RcaProperties("memory", null, null, splunk, null);
    }
}
