package com.srividhya.bankrca.source;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.srividhya.bankrca.rca.StackTraces.Frame;
import com.srividhya.bankrca.source.SourceLocation.CommitInfo;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stand-in for git on a local run: the sample source is described by a JSON file you can edit
 * (rca.source.stub-file, config/stub-source.json by default) - for each class, the lines
 * around the places the sample failures point at, and its commits. Commit times are given as
 * "hours ago", so they stay in step with the synthetic day. The file is read again on every
 * lookup; if it is not there, the copy built into the jar is used.
 */
@Component
@ConditionalOnProperty(name = "rca.source.mode", havingValue = "stub", matchIfMissing = true)
public class StubSourceRepository implements SourceRepository {

    static final String BUNDLED = "stub-source.json";
    private static final int CONTEXT = 5;

    /** The whole file. */
    public record StubSource(String repository, String ref, List<StubFile> files) {
    }

    /**
     * @param lineCount how long the real file would be; a frame beyond it is reported as a version mismatch
     * @param blocks the parts of the file that are spelled out
     * @param commits the file's history, in any order
     */
    public record StubFile(String className, String path, Integer lineCount, List<Block> blocks,
            List<StubCommit> commits) {
    }

    /** Consecutive lines of the file, starting at line {@code start}. */
    public record Block(int start, List<String> lines) {
    }

    /**
     * @param hoursAgo when it was committed, in hours before now
     * @param lines the line numbers it changed; a line no commit names belongs to the oldest commit
     */
    public record StubCommit(String hash, Double hoursAgo, String author, String message, List<Integer> lines) {
    }

    private final Path file;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public StubSourceRepository(SourceProperties props, Clock clock) {
        String configured = props.stubFile();
        this.file = Path.of(configured == null || configured.isBlank() ? "config/stub-source.json" : configured);
        this.clock = clock;
    }

    @Override
    public String description() {
        return "sample source from " + where() + " (no git)";
    }

    @Override
    public SourceLocation locate(String component, Frame frame) {
        StubSource source = load();
        StubFile f = source.files().stream().filter(x -> frame.className().equals(x.className())).findFirst()
                .orElse(null);
        if (f == null) {
            return SourceLocation.notFound(frame.className(), frame.method(), frame.line(),
                    frame.className() + " is not in " + where());
        }
        if (frame.line() == null) {
            return SourceLocation.notFound(frame.className(), frame.method(), null, "The frame has no line number");
        }
        if (f.lineCount() != null && frame.line() > f.lineCount()) {
            return SourceLocation.notFound(frame.className(), frame.method(), frame.line(),
                    f.path() + " has " + f.lineCount() + " lines at " + source.ref() + " but the trace points at line "
                            + frame.line() + ": the deployed version is probably different");
        }

        String code = null;
        StringBuilder snippet = new StringBuilder();
        for (Block block : f.blocks() == null ? List.<Block>of() : f.blocks()) {
            int end = block.start() + block.lines().size() - 1;
            if (frame.line() < block.start() || frame.line() > end) {
                continue;
            }
            code = block.lines().get(frame.line() - block.start()).strip();
            for (int n = Math.max(block.start(), frame.line() - CONTEXT); n <= Math.min(end, frame.line() + CONTEXT); n++) {
                snippet.append(n == frame.line() ? "> " : "  ").append("%4d | ".formatted(n))
                        .append(block.lines().get(n - block.start())).append('\n');
            }
        }
        if (code == null) {
            return SourceLocation.notFound(frame.className(), frame.method(), frame.line(),
                    where() + " has no code for line " + frame.line() + " of " + f.path());
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        List<StubCommit> newestFirst = new ArrayList<>(f.commits() == null ? List.<StubCommit>of() : f.commits());
        newestFirst.sort(Comparator.comparingDouble(c -> c.hoursAgo() == null ? Double.MAX_VALUE : c.hoursAgo()));
        List<CommitInfo> commits = newestFirst.stream().map(c -> new CommitInfo(c.hash(),
                now.minusSeconds((long) ((c.hoursAgo() == null ? 0 : c.hoursAgo()) * 3600)).toString(), c.author(),
                c.message())).toList();
        CommitInfo lineLastChanged = null;
        for (int i = 0; i < newestFirst.size(); i++) {
            List<Integer> lines = newestFirst.get(i).lines();
            if (lines != null && lines.contains(frame.line())) {
                lineLastChanged = commits.get(i);
                break;
            }
        }
        if (lineLastChanged == null && !commits.isEmpty()) {
            lineLastChanged = commits.get(commits.size() - 1);
        }
        return new SourceLocation(true, null, source.repository(), source.ref(), frame.className(), frame.method(),
                f.path(), frame.line(), code, snippet.toString(), lineLastChanged,
                commits.isEmpty() ? null : commits.get(0), commits.stream().limit(3).toList());
    }

    private String where() {
        return Files.isRegularFile(file) ? file.toString() : "the built-in " + BUNDLED;
    }

    StubSource load() {
        try {
            StubSource source;
            if (Files.isRegularFile(file)) {
                source = json.readValue(Files.readString(file), StubSource.class);
            } else {
                try (InputStream in = getClass().getClassLoader().getResourceAsStream(BUNDLED)) {
                    if (in == null) {
                        throw new IllegalStateException("No sample source: " + file + " does not exist and the jar has no " + BUNDLED);
                    }
                    source = json.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8), StubSource.class);
                }
            }
            if (source.files() == null) {
                throw new IllegalStateException(where() + " is not valid: 'files' is required");
            }
            for (int i = 0; i < source.files().size(); i++) {
                StubFile f = source.files().get(i);
                if (f.className() == null || f.path() == null) {
                    throw new IllegalStateException(where() + " is not valid: file #" + (i + 1)
                            + ": 'className' and 'path' are required");
                }
            }
            return source;
        } catch (JacksonException e) {
            throw new IllegalStateException(where() + " is not valid: " + e.getOriginalMessage()
                    + (e.getLocation() == null ? "" : " (line " + e.getLocation().getLineNr() + ")"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
