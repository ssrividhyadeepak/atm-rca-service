package com.srividhya.bankrca.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.srividhya.bankrca.rca.StackTraces.Frame;
import com.srividhya.bankrca.source.SourceProperties.Repo;

/** The prod code path against real git repositories built for the test. */
class GitSourceRepositoryTest {

    private static final String HOST_CLIENT = "payments/src/main/java/com/acme/host/HostClient.java";
    private static final Frame CALL = new Frame("com.acme.host.HostClient", "call", "HostClient.java", 12);

    @TempDir
    Path tmp;

    /** main: three commits to HostClient; tag "prod" on the second, so main has moved on. */
    private TestRepo payments() throws Exception {
        return new TestRepo(tmp.resolve("payments-remote"))
                .commit(HOST_CLIENT, TestRepo.file(14, "5", "    static final int TIMEOUT_MS = 5000;", "12",
                        "        return http.post(endpoint, request, TIMEOUT_MS);"), "priya", "2026-08-10T09:00:00Z", "initial import")
                .commit(HOST_CLIENT, TestRepo.file(14, "5", "    static final int TIMEOUT_MS = 500;", "12",
                        "        return http.post(endpoint, request, TIMEOUT_MS);"), "alex", "2026-09-30T16:40:00Z", "lower host timeout")
                .tag("prod")
                .commit(HOST_CLIENT, "// two new lines\n// at the top\n" + TestRepo.file(14, "5",
                        "    static final int TIMEOUT_MS = 500;", "12", "        return http.post(endpoint, request, TIMEOUT_MS);"),
                        "priya", "2026-10-02T08:20:00Z", "add metrics");
    }

    private SourceProperties single(String remoteUrl, Path repoDir, String ref) {
        return new SourceProperties("git", null, remoteUrl, "x-access-token", null, repoDir, tmp.resolve("mirrors"), ref,
                Duration.ZERO, null);
    }

    @Test
    void findsTheFrameInTheDeployedVersionWithBlameAndRecentCommits() throws Exception {
        try (TestRepo remote = payments()) {
            GitSourceRepository git = new GitSourceRepository(single(remote.dir().toString(), null, "prod"));

            SourceLocation at = git.locate("payments-p1", CALL);

            assertThat(at.found()).isTrue();
            assertThat(at.repository()).isEqualTo("default");
            assertThat(at.ref()).isEqualTo("prod");
            assertThat(at.path()).isEqualTo(HOST_CLIENT);
            assertThat(at.line()).isEqualTo(12);
            assertThat(at.code()).isEqualTo("return http.post(endpoint, request, TIMEOUT_MS);");
            assertThat(at.snippet()).contains(">   12 |         return http.post(endpoint, request, TIMEOUT_MS);")
                    .contains("     7 | // line 7").contains("    14 | // line 14");
            // The failing line is from the first commit; the file's latest commit at 'prod' is the timeout change
            assertThat(at.lineLastChanged().message()).isEqualTo("initial import");
            assertThat(at.lineLastChanged().author()).isEqualTo("priya");
            assertThat(at.fileLastChanged().message()).isEqualTo("lower host timeout");
            assertThat(at.fileLastChanged().time()).isEqualTo("2026-09-30T16:40:00Z");
            assertThat(at.fileLastChanged().hash()).matches("[0-9a-f]{10}");
            // The commit made after the deployment is not part of the deployed history
            assertThat(at.recentCommits()).extracting(SourceLocation.CommitInfo::message)
                    .containsExactly("lower host timeout", "initial import");
            assertThat(git.description()).startsWith("git: default (mirror of ").endsWith("ref prod)");
            // A mirror: nothing is checked out
            assertThat(tmp.resolve("mirrors/default/payments")).doesNotExist();
        }
    }

    @Test
    void theSameLineOnAnotherRefIsDifferentCode() throws Exception {
        try (TestRepo remote = payments()) {
            GitSourceRepository git = new GitSourceRepository(single(remote.dir().toString(), null, "main"));

            assertThat(git.locate(null, CALL).code()).isEqualTo("// line 10");
        }
    }

    @Test
    void saysWhyAFrameCouldNotBeLocated() throws Exception {
        try (TestRepo remote = payments()) {
            GitSourceRepository git = new GitSourceRepository(single(remote.dir().toString(), null, "prod"));

            SourceLocation missing = git.locate(null, new Frame("com.acme.other.Missing", "run", "Missing.java", 3));
            assertThat(missing.found()).isFalse();
            assertThat(missing.note()).isEqualTo("com.acme.other.Missing was not found in default at prod");

            SourceLocation beyond = git.locate(null, new Frame("com.acme.host.HostClient", "call", "HostClient.java", 900));
            assertThat(beyond.found()).isFalse();
            assertThat(beyond.note()).contains("has 14 lines in default at prod").contains("line 900")
                    .contains("deployed version is probably different");

            assertThat(git.locate(null, new Frame("com.acme.host.HostClient", "call", null, null)).note())
                    .isEqualTo("The frame has no line number");

            GitSourceRepository wrongRef = new GitSourceRepository(new SourceProperties("git", null,
                    remote.dir().toString(), null, null, null, tmp.resolve("mirrors2"), "release-9", Duration.ZERO, null));
            assertThat(wrongRef.locate(null, CALL).note()).isEqualTo("repository default has no ref 'release-9'");
        }
    }

    @Test
    void picksUpNewCommitsAndTagsFromTheRemote() throws Exception {
        try (TestRepo remote = payments()) {
            GitSourceRepository git = new GitSourceRepository(single(remote.dir().toString(), null, "release-2"));
            assertThat(git.locate(null, CALL).found()).isFalse();

            remote.commit(HOST_CLIENT, TestRepo.file(14, "12", "        return retry(() -> http.post(endpoint, request));"),
                    "sam", "2026-10-03T10:00:00Z", "add retry").tag("release-2");

            SourceLocation at = git.locate(null, CALL);
            assertThat(at.code()).isEqualTo("return retry(() -> http.post(endpoint, request));");
            assertThat(at.lineLastChanged().message()).isEqualTo("add retry");
        }
    }

    @Test
    void looksInTheRepositoryConfiguredForTheComponent() throws Exception {
        try (TestRepo payments = payments();
                TestRepo deposits = new TestRepo(tmp.resolve("deposits-clone")).commit(
                        "src/main/java/com/acme/deposit/Validator.java", TestRepo.file(8, "4", "        String c = envelope.getCurrency();"),
                        "mei", "2026-09-29T10:15:00Z", "currency check")) {
            GitSourceRepository git = new GitSourceRepository(new SourceProperties("git", null, null, null, null, null,
                    tmp.resolve("mirrors"), "main", Duration.ofMinutes(15), List.of(
                            new Repo("payments", payments.dir().toString(), null, "prod", List.of("payments-*")),
                            // an existing clone, read as it is, on the common ref
                            new Repo("deposits", null, deposits.dir(), null, List.of("deposit-*")))));

            SourceLocation deposit = git.locate("deposit-p1", new Frame("com.acme.deposit.Validator", "validate", "Validator.java", 4));
            assertThat(deposit.found()).isTrue();
            assertThat(deposit.repository()).isEqualTo("deposits");
            assertThat(deposit.ref()).isEqualTo("main");
            assertThat(deposit.code()).isEqualTo("String c = envelope.getCurrency();");

            assertThat(git.locate("payments-p1", CALL).repository()).isEqualTo("payments");
            // A class of another component is not searched for in this component's repository
            assertThat(git.locate("deposit-p1", CALL).note()).isEqualTo("com.acme.host.HostClient was not found in deposits at main");
            // An unknown component: every repository is tried
            assertThat(git.locate("something-else", CALL).found()).isTrue();
            assertThat(git.description()).contains("payments (mirror of").contains("deposits (clone at");
        }
    }

    @Test
    void refusesToStartWithoutCodeOrWithAnUnsafeRemote() {
        assertThatThrownBy(() -> new GitSourceRepository(single(null, tmp.resolve("nothing-here"), "main")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("there is no code to read")
                .hasMessageContaining("GIT_REMOTE_URL");
        assertThat(tmp.resolve("nothing-here")).doesNotExist();

        assertThatThrownBy(() -> new GitSourceRepository(single("http://git.example.com/bank/app.git", null, "main")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("must use https");
        assertThatThrownBy(() -> new GitSourceRepository(single("https://me:secret-token@git.example.com/bank/app.git", null, "main")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("must not contain credentials")
                .message().doesNotContain("secret-token");
        assertThatThrownBy(() -> new GitSourceRepository(single(tmp.resolve("no-such-remote").toString(), null, "main")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Could not clone repository 'default'");
        assertThatThrownBy(() -> new GitSourceRepository(new SourceProperties("git", null, null, null, null, null,
                tmp.resolve("m"), "main", null, List.of(new Repo("../escape", "https://git.example.com/x.git", null, null, null)))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("needs a 'name'");
        assertThatThrownBy(() -> new GitSourceRepository(new SourceProperties("git", null, null, null, null, null,
                tmp.resolve("m"), "main", null, List.of(new Repo("empty", null, null, null, null)))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("needs a 'remote-url' to mirror or a 'path'");
    }

    @Test
    void neverPrintsTheToken() {
        SourceProperties props = new SourceProperties("git", null, "https://git.example.com/x.git", "me", "ghp_secret",
                null, null, "main", null, null);

        assertThat(props.toString()).doesNotContain("ghp_secret");
    }

    @Test
    void listsTheCommitsOfTheDeployedRefInAWindowWithTheirFiles() throws Exception {
        try (TestRepo remote = payments()) {
            remote.commit("payments/src/main/resources/application.yml", "timeout: 500\n", "alex", "2026-10-03T10:00:00Z",
                    "config: timeout (CHG0041001)");
            GitSourceRepository git = new GitSourceRepository(single(remote.dir().toString(), null, "main"));

            List<CommitChange> commits = git.commits("payments-p1", java.time.Instant.parse("2026-09-01T00:00:00Z"),
                    java.time.Instant.parse("2026-10-03T12:00:00Z"), 10);

            // Newest first; the August import is outside the window
            assertThat(commits).extracting(CommitChange::message).containsExactly("config: timeout (CHG0041001)",
                    "add metrics", "lower host timeout");
            assertThat(commits.get(0).files()).containsExactly("payments/src/main/resources/application.yml");
            assertThat(commits.get(0).time()).isEqualTo("2026-10-03T10:00:00Z");
            assertThat(commits.get(2).files()).containsExactly(HOST_CLIENT);
            assertThat(commits.get(2).author()).isEqualTo("alex");

            SuspectService suspects = new SuspectService(git, new com.srividhya.bankrca.security.PiiMasker());
            SuspectService.SuspectSearch found = suspects.find("payments-p1", java.time.Instant.parse("2026-10-03T10:30:00Z"),
                    Duration.ofDays(7), "com.acme.host.HostClient");
            assertThat(found.suspects()).extracting(s -> s.message() + " " + s.score()).containsExactly(
                    // Both code commits are over a day old (18) and change the class (35) and code (15);
                    // the config commit is 30 minutes old (50) and changes config (15)
                    "add metrics 68", "lower host timeout 68", "config: timeout (CHG0041001) 65");
        }
    }
}
