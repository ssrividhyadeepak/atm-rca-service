package com.srividhya.bankrca.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.srividhya.bankrca.rca.StackTraces.Frame;

class StubSourceRepositoryTest {

    private static final String FILE = """
            {"repository":"sample","ref":"prod","files":[
             {"className":"com.acme.host.HostClient","path":"pay/src/main/java/com/acme/host/HostClient.java","lineCount":40,
              "blocks":[{"start":10,"lines":["    int a = 1;","    int b = 2;","    return call(a, b);","}"]}],
              "commits":[{"hash":"old0000001","hoursAgo":1000,"author":"priya","message":"initial import"},
                         {"hash":"new0000002","hoursAgo":12,"author":"alex","message":"lower timeout","lines":[11]}]}]}""";

    @TempDir
    Path tmp;

    private StubSourceRepository stub(Path file) {
        return new StubSourceRepository(new SourceProperties("stub", file.toString(), null, null, null, null, null, null,
                null, null), Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC));
    }

    private static Frame frame(int line) {
        return new Frame("com.acme.host.HostClient", "call", "HostClient.java", line);
    }

    @Test
    void answersFromTheFile() throws Exception {
        Path file = tmp.resolve("source.json");
        Files.writeString(file, FILE);

        SourceLocation at = stub(file).locate("pay-p1", frame(12));

        assertThat(at.found()).isTrue();
        assertThat(at.repository()).isEqualTo("sample");
        assertThat(at.ref()).isEqualTo("prod");
        assertThat(at.path()).isEqualTo("pay/src/main/java/com/acme/host/HostClient.java");
        assertThat(at.code()).isEqualTo("return call(a, b);");
        assertThat(at.snippet()).isEqualTo("    10 |     int a = 1;\n    11 |     int b = 2;\n>   12 |     return call(a, b);\n    13 | }\n");
        // No commit names line 12, so it belongs to the oldest; the newest commit is the file's latest
        assertThat(at.lineLastChanged().hash()).isEqualTo("old0000001");
        assertThat(at.fileLastChanged().hash()).isEqualTo("new0000002");
        // "hours ago" is counted back from now
        assertThat(at.fileLastChanged().time()).isEqualTo("2026-10-01T12:00:00Z");
        assertThat(at.recentCommits()).hasSize(2);
        // Line 11 is named by the newer commit
        assertThat(stub(file).locate(null, frame(11)).lineLastChanged().hash()).isEqualTo("new0000002");
    }

    @Test
    void saysWhyAFrameCouldNotBeLocated() throws Exception {
        Path file = tmp.resolve("source.json");
        Files.writeString(file, FILE);
        StubSourceRepository stub = stub(file);

        assertThat(stub.locate(null, new Frame("com.acme.Missing", "run", "Missing.java", 3)).note())
                .isEqualTo("com.acme.Missing is not in " + file);
        assertThat(stub.locate(null, frame(900)).note()).contains("has 40 lines at prod").contains("line 900");
        assertThat(stub.locate(null, frame(30)).note()).contains("has no code for line 30");
        assertThat(stub.locate(null, frame(30)).found()).isFalse();
    }

    @Test
    void picksUpAnEditAndReportsABrokenFile() throws Exception {
        Path file = tmp.resolve("source.json");
        Files.writeString(file, FILE);
        StubSourceRepository stub = stub(file);
        assertThat(stub.locate(null, frame(12)).code()).isEqualTo("return call(a, b);");

        Files.writeString(file, FILE.replace("return call(a, b);", "return retry(a, b);"));
        assertThat(stub.locate(null, frame(12)).code()).isEqualTo("return retry(a, b);");

        Files.writeString(file, FILE.replace("\"lineCount\"", "\"lineCnt\""));
        assertThatThrownBy(() -> stub.locate(null, frame(12))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(file + " is not valid").hasMessageContaining("lineCnt");
    }

    @Test
    void fallsBackToTheBuiltInSampleWhenTheFileIsMissing() {
        StubSourceRepository stub = stub(tmp.resolve("no-such-file.json"));

        assertThat(stub.description()).isEqualTo("sample source from the built-in stub-source.json (no git)");
        SourceLocation at = stub.locate("withdrawal-p1",
                new Frame("com.example.bank.withdrawal.host.HostAuthClient", "call", "HostAuthClient.java", 73));
        assertThat(at.code()).isEqualTo("return http.post(endpoint + \"/authorize\", request, TIMEOUT_MS);");
        assertThat(at.fileLastChanged().message()).isEqualTo("withdrawal: lower host auth timeout for faster failover");
    }
}
