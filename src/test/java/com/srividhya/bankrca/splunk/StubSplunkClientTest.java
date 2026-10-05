package com.srividhya.bankrca.splunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.srividhya.bankrca.config.RcaProperties;
import com.srividhya.bankrca.failure.RawEventParser;
import com.srividhya.bankrca.failure.RawEventParser.Parsed;

/** The stub's failures come from a JSON file that can be edited while the service runs. */
class StubSplunkClientTest {

    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");
    private static final Instant DAY_AGO = Instant.parse("2026-10-01T00:00:00Z");

    @TempDir
    Path tmp;

    private StubSplunkClient stub(Path file) {
        return new StubSplunkClient(Clock.fixed(NOW, ZoneOffset.UTC),
                new RcaProperties("memory", null, null, null, new RcaProperties.Stub(file.toString(), null)));
    }

    private List<Map<String, Object>> search(StubSplunkClient stub, String namespace, String... components) {
        return stub.search(SplunkClient.FAILED_TRANSACTIONS, DAY_AGO, NOW,
                Map.of("namespace", namespace, "components", List.of(components), "limit", 1000));
    }

    @Test
    void generatesTheEventsTheFileDescribes() throws Exception {
        Path file = tmp.resolve("failures.json");
        Files.writeString(file, """
                {"namespace":"payments","cluster":"west2","datacenter":"dc9","bankIds":["Z9001"],
                 "failures":[
                  {"component":"transfer-p1","logger":"com.acme.TransferClient","level":"ERROR",
                   "exception":"com.acme.TransferRejectedException","message":"rejected code={int:7-7}",
                   "stackTrace":["at com.acme.TransferClient.send(TransferClient.java:12)","Caused by: java.io.IOException: reset"],
                   "count":5,"fromHoursAgo":3,"toHoursAgo":1},
                  {"component":"kiosk-ui-p1","logger":"com.acme.KioskController","level":"INFO","uiEvent":true,
                   "message":"screen failed","count":2}]}""");
        StubSplunkClient stub = stub(file);

        List<Map<String, Object>> rows = search(stub, "payments", "transfer-*");

        assertThat(rows).hasSize(5);
        assertThat(stub.description()).isEqualTo("synthetic events from " + file + " (no Splunk)");
        Parsed p = new RawEventParser().parse((String) rows.get(0).get("_raw"), null);
        assertThat(p.component()).isEqualTo("transfer-p1");
        assertThat(p.namespace()).isEqualTo("payments");
        assertThat(p.cluster()).isEqualTo("west2");
        assertThat(p.bankId()).isEqualTo("Z9001");
        assertThat(p.exception()).isEqualTo("com.acme.TransferRejectedException");
        assertThat(p.message()).endsWith("rejected code=7");
        assertThat(p.stackTrace()).contains("\tat com.acme.TransferClient.send(TransferClient.java:12)")
                .contains("\nCaused by: java.io.IOException: reset");
        // Inside the window the file gives: between 3 hours and 1 hour before now
        assertThat(rows).allSatisfy(r -> assertThat(Instant.parse((String) r.get("ts")))
                .isBetween(Instant.parse("2026-10-01T21:00:00Z"), Instant.parse("2026-10-01T23:00:00Z")));

        assertThat(search(stub, "payments", "kiosk-ui-*")).hasSize(2);
        assertThat(search(stub, "payments", "*")).hasSize(7);
        assertThat(search(stub, "another-namespace", "*")).isEmpty();
    }

    @Test
    void picksUpAnEditWithoutARestart() throws Exception {
        Path file = tmp.resolve("failures.json");
        String template = "{\"namespace\":\"prod\",\"bankIds\":[\"Q1231\"],\"failures\":[{\"component\":\"withdrawal-p1\","
                + "\"logger\":\"com.acme.A\",\"message\":\"boom\",\"count\":%d}]}";
        Files.writeString(file, template.formatted(3));
        StubSplunkClient stub = stub(file);
        assertThat(search(stub, "prod", "withdrawal-*")).hasSize(3);

        Files.writeString(file, template.formatted(8));

        assertThat(search(stub, "prod", "withdrawal-*")).hasSize(8);
    }

    @Test
    void saysWhatIsWrongWithTheFile() throws Exception {
        Path file = tmp.resolve("failures.json");
        StubSplunkClient stub = stub(file);

        Files.writeString(file, "{\"namespace\":\"prod\",\"bankIds\":[\"Q1\"],\n\"failures\":[{\"component\":\"a\",]}");
        assertThatThrownBy(() -> search(stub, "prod", "*")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(file + " is not valid").hasMessageContaining("line 2");

        Files.writeString(file, "{\"namespace\":\"prod\",\"bankIds\":[\"Q1\"],\"failures\":[{\"component\":\"a\","
                + "\"logger\":\"L\",\"message\":\"m\",\"count\":1},{\"component\":\"b\",\"logger\":\"L\",\"message\":\"m\"}]}");
        assertThatThrownBy(() -> search(stub, "prod", "*"))
                .hasMessageContaining("failure #2: 'count' is required");

        Files.writeString(file, "{\"namespace\":\"prod\",\"bankIds\":[\"Q1\"],\"failures\":[{\"component\":\"a\","
                + "\"logger\":\"L\",\"mesage\":\"typo\",\"count\":1}]}");
        assertThatThrownBy(() -> search(stub, "prod", "*")).hasMessageContaining("mesage");

        Files.writeString(file, "{\"namespace\":\"prod\",\"bankIds\":[\"Q1\"],\"failures\":[{\"component\":\"a\","
                + "\"logger\":\"L\",\"message\":\"m\",\"count\":1,\"fromHoursAgo\":2,\"toHoursAgo\":5}]}");
        assertThatThrownBy(() -> search(stub, "prod", "*"))
                .hasMessageContaining("'fromHoursAgo' must be greater than 'toHoursAgo'");
    }

    @Test
    void fallsBackToTheBuiltInDataWhenTheFileIsMissing() {
        StubSplunkClient stub = stub(tmp.resolve("no-such-file.json"));

        assertThat(stub.description()).isEqualTo("synthetic events from the built-in stub-failures.json (no Splunk)");
        assertThat(search(stub, "prod", "withdrawal-*", "deposit-*", "balance-*", "ui-base-*")).hasSize(149);
    }
}
