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
    private static final String SPL = "search index=$index$ \"*exception*\" kubernetes.namespace_name=\"$namespace$\" "
            + "kubernetes.container_name IN ($components$) | head $limit$";
    private static final Map<String, Object> ARGS = Map.of("namespace", "atm-prod", "components",
            List.of("app-atm-withdrawal-*", "app-atm-deposit-prod1"), "limit", 500);

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
                .containsEntry("search", "search index=atm_app \"*exception*\" kubernetes.namespace_name=\"atm-prod\" "
                        + "kubernetes.container_name IN (\"app-atm-withdrawal-*\",\"app-atm-deposit-prod1\") | head 500")
                .containsEntry("earliest_time", "1790812800.000").containsEntry("latest_time", "1790899200.000")
                .containsEntry("exec_mode", "oneshot").containsEntry("output_mode", "json").containsEntry("count", "1000");
    }

    @Test
    void logsInOnceWithUsernameAndPasswordThenUsesTheSessionKey() {
        LiveSplunkClient client = client(null, " jdoe ", "s3cret pw&=");

        client.search(SplunkClient.FAILED_TRANSACTIONS, FROM, TO, ARGS);
        client.search(SplunkClient.FAILED_TRANSACTIONS, FROM, TO, ARGS);
        client.search(SplunkClient.FAILED_TRANSACTIONS, FROM, TO, ARGS);

        assertThat(splunk.logins).hasValue(1);
        assertThat(splunk.loginForm).containsEntry("username", "jdoe").containsEntry("password", "s3cret pw&=");
        assertThat(splunk.searches).hasValue(3);
        // The searches carry the session key, never the password
        assertThat(splunk.authorization).isEqualTo("Splunk session-1");
        assertThat(splunk.form.toString()).doesNotContain("s3cret");
    }

    @Test
    void logsInAgainOnceWhenTheSessionHasExpired() {
        LiveSplunkClient client = client(null, "jdoe", "s3cret");
        client.search(SplunkClient.FAILED_TRANSACTIONS, FROM, TO, ARGS);

        // Splunk forgets session-1: the next search is refused until a new login
        splunk.logins.incrementAndGet();
        splunk.expireSessions = true;
        client.search(SplunkClient.FAILED_TRANSACTIONS, FROM, TO, ARGS);

        assertThat(splunk.logins).as("one real re-login").hasValue(3);
        assertThat(splunk.authorization).isEqualTo("Splunk session-3");
    }

    @Test
    void stopsTryingAfterAWrongPasswordSoTheAccountIsNotLocked() {
        splunk.loginStatus = 401;
        LiveSplunkClient client = client(null, "jdoe", "wrong-pw");

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> client.search(SplunkClient.FAILED_TRANSACTIONS, FROM, TO, ARGS))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("rejected the credentials")
                    .hasMessageContaining("Not trying again until the service is restarted")
                    .message().doesNotContain("wrong-pw");
        }

        assertThat(splunk.logins).as("only the first attempt reached Splunk").hasValue(1);
        assertThat(splunk.searches).hasValue(0);
    }

    @Test
    void stopsTryingAfterARejectedToken() {
        splunk.status = 401;
        LiveSplunkClient client = client("tok-123", null, null);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.search(SplunkClient.FAILED_TRANSACTIONS, FROM, TO, ARGS))
                    .hasMessageContaining("rejected the credentials").hasMessageContaining("SPLUNK_TOKEN")
                    .message().doesNotContain("tok-123");
        }

        assertThat(splunk.searches).hasValue(1);
    }

    @Test
    void returnsTheEventTimeAndTheRawEvent() {
        splunk.response = """
                {"results":[{"ts":"1790860412.337","_raw":"{\\"@timestamp\\":\\"2026-10-01T13:13:32.337Z\\",\\"message\\":\\"boom\\"}"},
                 {"ts":"1790860413","_raw":["line one","line two"]}]}""";

        List<Map<String, Object>> rows = client("t", null, null).search(SplunkClient.FAILED_TRANSACTIONS, FROM, TO, ARGS);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("ts", "2026-10-01T13:13:32.337Z")
                .containsEntry("_raw", "{\"@timestamp\":\"2026-10-01T13:13:32.337Z\",\"message\":\"boom\"}");
        assertThat(rows.get(1)).containsEntry("_raw", "line one\nline two");
    }

    @Test
    void refusesArgumentsThatCouldChangeTheSearch() {
        LiveSplunkClient client = client("t", null, null);

        for (String bad : List.of("x\" | delete", "a | outputlookup x", "[search index=*]", "a`b", "a) OR (b", "")) {
            assertThatThrownBy(() -> client.search(SplunkClient.FAILED_TRANSACTIONS, FROM, TO,
                    Map.of("namespace", "atm-prod", "components", List.of("app-atm-withdrawal-*", bad), "limit", 10)))
                    .as(bad).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not allowed");
        }
        assertThatThrownBy(() -> client.search(SplunkClient.FAILED_TRANSACTIONS, FROM, TO,
                Map.of("namespace", "atm-prod", "components", List.of(), "limit", 10))).hasMessageContaining("is empty");
        assertThatThrownBy(() -> client.search(SplunkClient.FAILED_TRANSACTIONS, FROM, TO,
                Map.of("namespace", "x\" OR index=\"*", "components", List.of("a"), "limit", 10)))
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> client.search("search index=* | delete", FROM, TO, Map.of()))
                .hasMessageContaining("Unknown search");
        assertThat(splunk.path).as("nothing was sent to Splunk").isNull();
    }

    @Test
    void explainsFailuresWithoutEchoingCredentials() {
        splunk.status = 403;
        assertThatThrownBy(() -> client("tok-123", null, null).search(SplunkClient.FAILED_TRANSACTIONS, FROM, TO, ARGS))
                .hasMessageContaining("not allowed to run this search (403)");

        splunk.stop();
        assertThatThrownBy(() -> client("tok-123", null, null).search(SplunkClient.FAILED_TRANSACTIONS, FROM, TO, ARGS))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Could not reach Splunk at http://127.0.0.1:")
                .message().doesNotContain("tok-123");
        assertThatThrownBy(() -> client(null, "jdoe", "s3cret").search(SplunkClient.FAILED_TRANSACTIONS, FROM, TO, ARGS))
                .hasMessageContaining("Could not reach Splunk").message().doesNotContain("s3cret");
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
