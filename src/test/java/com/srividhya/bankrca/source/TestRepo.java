package com.srividhya.bankrca.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;

/** Builds a small real git repository in a temp folder, to play a remote or an existing clone. */
public final class TestRepo implements AutoCloseable {

    private final Path dir;
    private final Git git;

    public TestRepo(Path dir) throws Exception {
        this.dir = dir;
        IsolatedGitConfig.install();
        this.git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call();
    }

    public Path dir() {
        return dir;
    }

    public TestRepo commit(String path, String content, String author, String when, String message) throws Exception {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        PersonIdent ident = new PersonIdent(author, author + "@example.com", Instant.parse(when), ZoneOffset.UTC);
        git.add().addFilepattern(".").call();
        git.commit().setAuthor(ident).setCommitter(ident).setMessage(message).setSign(false).call();
        return this;
    }

    public TestRepo tag(String name) throws Exception {
        git.tag().setName(name).setAnnotated(false).call();
        return this;
    }

    /** Lines "line 1" .. "line n", with the given lines replaced. */
    public static String file(int lines, String... replacements) {
        StringBuilder sb = new StringBuilder();
        for (int n = 1; n <= lines; n++) {
            String text = "// line " + n;
            for (int i = 0; i < replacements.length; i += 2) {
                if (Integer.parseInt(replacements[i]) == n) {
                    text = replacements[i + 1];
                }
            }
            sb.append(text).append('\n');
        }
        return sb.toString();
    }

    @Override
    public void close() throws IOException {
        git.close();
    }
}
