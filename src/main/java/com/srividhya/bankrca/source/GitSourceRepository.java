package com.srividhya.bankrca.source;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.srividhya.bankrca.rca.StackTraces.Frame;
import com.srividhya.bankrca.source.SourceLocation.CommitInfo;
import com.srividhya.bankrca.source.SourceProperties.Repo;

/**
 * The real implementation: looks frames up in git, read-only, with JGit. Everything is read
 * from git objects at the deployed ref, never from a working tree, so the answer matches what
 * production runs.
 *
 * Each repository is either mirrored from a remote (cloned at startup, fetched again whenever
 * a lookup finds the last fetch older than fetch-interval) or an existing clone read as it is.
 * With several repositories, a component's code is looked for in the repositories configured
 * for it first.
 */
@Component
@ConditionalOnProperty(name = "rca.source.mode", havingValue = "git")
public class GitSourceRepository implements SourceRepository {

    private static final Logger log = LoggerFactory.getLogger(GitSourceRepository.class);
    private static final int CONTEXT = 5;
    private static final int RECENT_COMMITS = 3;

    private static final class Source {
        String name;
        Path dir;
        String remoteUrl;
        String ref;
        List<Pattern> components = new ArrayList<>();
        long lastFetch;
    }

    private final List<Source> sources = new ArrayList<>();
    private final CredentialsProvider credentials;
    private final long fetchIntervalNanos;

    public GitSourceRepository(SourceProperties props) {
        this.credentials = props.token() == null || props.token().isBlank() ? null
                : new UsernamePasswordCredentialsProvider(props.username() == null ? "x-access-token" : props.username(),
                        props.token().strip());
        this.fetchIntervalNanos = (props.fetchInterval() == null ? Duration.ofMinutes(15) : props.fetchInterval()).toNanos();
        String commonRef = props.deployedRef() == null || props.deployedRef().isBlank() ? "main" : props.deployedRef();
        IsolatedGitConfig.install();

        List<Repo> configured = new ArrayList<>(props.repositories() == null ? List.of() : props.repositories());
        if (configured.isEmpty()) {
            // The single-repository form: GIT_REMOTE_URL, or an existing clone at RCA_REPO_DIR
            boolean hasRemote = props.remoteUrl() != null && !props.remoteUrl().isBlank();
            if (!hasRemote && (props.repoDir() == null || !isRepository(props.repoDir()))) {
                throw new IllegalStateException("rca.source.mode is git but there is no code to read. Set GIT_REMOTE_URL "
                        + "(and GIT_TOKEN) to mirror a repository, RCA_REPO_DIR to an existing clone, or list "
                        + "rca.source.repositories in config/application-prod.yml");
            }
            configured.add(new Repo("default", hasRemote ? props.remoteUrl() : null, hasRemote ? null : props.repoDir(),
                    null, null));
        }
        for (Repo repo : configured) {
            sources.add(open(repo, props, commonRef));
        }
    }

    private Source open(Repo repo, SourceProperties props, String commonRef) {
        if (repo.name() == null || !repo.name().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalStateException("Each rca.source.repositories entry needs a 'name' of letters, digits, '.', '_' or '-'");
        }
        Source s = new Source();
        s.name = repo.name();
        s.ref = repo.deployedRef() == null || repo.deployedRef().isBlank() ? commonRef : repo.deployedRef();
        for (String pattern : repo.components() == null ? List.<String>of() : repo.components()) {
            s.components.add(Pattern.compile(Pattern.quote(pattern).replace("*", "\\E.*\\Q")));
        }
        boolean hasRemote = repo.remoteUrl() != null && !repo.remoteUrl().isBlank();
        if (hasRemote) {
            s.remoteUrl = repo.remoteUrl().strip();
            requireSafeRemote(s.name, s.remoteUrl);
            Path base = props.reposDir() == null ? Path.of("data/repos") : props.reposDir();
            s.dir = base.resolve(s.name);
            if (isRepository(s.dir)) {
                fetch(s);
            } else {
                mirror(s);
            }
            s.lastFetch = System.nanoTime();
        } else if (repo.path() != null && isRepository(repo.path())) {
            s.dir = repo.path();
        } else {
            throw new IllegalStateException("Repository '" + s.name + "' needs a 'remote-url' to mirror or a 'path' to an "
                    + "existing clone" + (repo.path() == null ? "" : "; " + repo.path() + " is not a git repository"));
        }
        return s;
    }

    @Override
    public String description() {
        List<String> parts = sources.stream()
                .map(s -> s.name + " (" + (s.remoteUrl == null ? "clone at " + s.dir : "mirror of " + s.remoteUrl)
                        + ", ref " + s.ref + ")").toList();
        return "git: " + String.join("; ", parts);
    }

    @Override
    public SourceLocation locate(String component, Frame frame) {
        if (frame.line() == null) {
            return SourceLocation.notFound(frame.className(), frame.method(), null, "The frame has no line number");
        }
        // com.example.bank.Foo + Foo.java -> any path ending in /com/example/bank/Foo.java
        int lastDot = frame.className().lastIndexOf('.');
        String fileName = frame.file() != null ? frame.file() : frame.className().substring(lastDot + 1) + ".java";
        String suffix = (lastDot < 0 ? "" : "/" + frame.className().substring(0, lastDot).replace('.', '/')) + "/" + fileName;

        List<String> notes = new ArrayList<>();
        for (Source s : candidates(component)) {
            try {
                SourceLocation found = locateIn(s, frame, suffix, notes);
                if (found != null) {
                    return found;
                }
            } catch (IOException | GitAPIException | RuntimeException e) {
                log.warn("Lookup of {} in repository {} failed: {}", frame.className(), s.name, e.toString());
                notes.add("lookup in " + s.name + " failed (" + e.getClass().getSimpleName() + ")");
            }
        }
        return SourceLocation.notFound(frame.className(), frame.method(), frame.line(), notes.isEmpty()
                ? frame.className() + " was not found in " + names(candidates(component))
                : String.join("; ", notes));
    }

    private SourceLocation locateIn(Source s, Frame frame, String suffix, List<String> notes)
            throws IOException, GitAPIException {
        fetchIfStale(s);
        try (Git git = Git.open(s.dir.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repo = git.getRepository();
            ObjectId commit = repo.resolve(s.ref + "^{commit}");
            if (commit == null) {
                notes.add("repository " + s.name + " has no ref '" + s.ref + "'");
                return null;
            }
            List<String> paths = new ArrayList<>();
            try (TreeWalk tree = new TreeWalk(repo)) {
                tree.addTree(walk.parseCommit(commit).getTree());
                tree.setRecursive(true);
                tree.setFilter(PathSuffixFilter.create(suffix));
                while (tree.next()) {
                    paths.add(tree.getPathString());
                }
            }
            if (paths.isEmpty()) {
                notes.add(frame.className() + " was not found in " + s.name + " at " + s.ref);
                return null;
            }
            paths.sort(null);
            String path = paths.get(0);
            List<String> lines;
            try (TreeWalk tree = TreeWalk.forPath(repo, path, walk.parseCommit(commit).getTree())) {
                lines = new String(repo.open(tree.getObjectId(0)).getBytes(), StandardCharsets.UTF_8).lines().toList();
            }
            if (frame.line() > lines.size()) {
                notes.add(path + " has " + lines.size() + " lines in " + s.name + " at " + s.ref + " but the trace points "
                        + "at line " + frame.line() + ": the deployed version is probably different");
                return null;
            }
            StringBuilder snippet = new StringBuilder();
            for (int n = Math.max(1, frame.line() - CONTEXT); n <= Math.min(lines.size(), frame.line() + CONTEXT); n++) {
                snippet.append(n == frame.line() ? "> " : "  ").append("%4d | ".formatted(n)).append(lines.get(n - 1))
                        .append('\n');
            }
            BlameResult blame = git.blame().setStartCommit(commit).setFilePath(path).call();
            RevCommit blamed = blame == null ? null : blame.getSourceCommit(frame.line() - 1);
            List<CommitInfo> recent = new ArrayList<>();
            for (RevCommit c : git.log().add(commit).addPath(path).setMaxCount(RECENT_COMMITS).call()) {
                recent.add(info(c));
            }
            return new SourceLocation(true, null, s.name, s.ref, frame.className(), frame.method(), path, frame.line(),
                    lines.get(frame.line() - 1).strip(), snippet.toString(), blamed == null ? null : info(blamed),
                    recent.isEmpty() ? null : recent.get(0), recent);
        }
    }

    /** Repositories configured for the component first, then those open to any component. */
    private List<Source> candidates(String component) {
        List<Source> matching = new ArrayList<>();
        List<Source> open = new ArrayList<>();
        for (Source s : sources) {
            if (s.components.isEmpty()) {
                open.add(s);
            } else if (component != null && s.components.stream().anyMatch(p -> p.matcher(component).matches())) {
                matching.add(s);
            }
        }
        matching.addAll(open);
        return matching.isEmpty() ? sources : matching;
    }

    private static String names(List<Source> list) {
        return String.join(", ", list.stream().map(s -> s.name + " at " + s.ref).toList());
    }

    /** A mirror has the remote's branches and tags under their own names, so refs resolve as on the remote. */
    private void mirror(Source s) {
        log.info("Mirroring {} into {}", s.remoteUrl, s.dir);
        try {
            Files.createDirectories(s.dir);
            Git.cloneRepository().setURI(s.remoteUrl).setDirectory(s.dir.toFile()).setMirror(true)
                    .setCredentialsProvider(credentials).call().close();
        } catch (GitAPIException | IOException e) {
            throw new IllegalStateException("Could not clone repository '" + s.name + "' from " + s.remoteUrl + ": "
                    + e.getMessage() + ". Check the URL, GIT_USERNAME and GIT_TOKEN");
        }
    }

    /** A failed fetch is logged, not fatal: lookups carry on against what was fetched last. */
    private void fetch(Source s) {
        try (Git git = Git.open(s.dir.toFile())) {
            git.fetch().setRemote("origin").setRemoveDeletedRefs(true).setCredentialsProvider(credentials).call();
        } catch (GitAPIException | IOException e) {
            log.warn("Could not fetch repository {}: {}. Using the code fetched earlier", s.name, e.getMessage());
        }
    }

    private void fetchIfStale(Source s) {
        synchronized (s) {
            if (s.remoteUrl != null && System.nanoTime() - s.lastFetch >= fetchIntervalNanos) {
                fetch(s);
                s.lastFetch = System.nanoTime();
            }
        }
    }

    private static boolean isRepository(Path dir) {
        // a normal clone, or a bare / mirror repository
        return Files.isDirectory(dir.resolve(".git"))
                || (Files.isRegularFile(dir.resolve("HEAD")) && Files.isDirectory(dir.resolve("objects")));
    }

    private void requireSafeRemote(String name, String url) {
        URI uri = URI.create(url);
        if (uri.getUserInfo() != null) {
            throw new IllegalStateException("The remote URL of repository '" + name + "' must not contain credentials; "
                    + "put the token in GIT_TOKEN");
        }
        boolean local = uri.getScheme() == null || "file".equals(uri.getScheme());
        if (!local && !"https".equals(uri.getScheme())) {
            throw new IllegalStateException("The remote URL of repository '" + name + "' must use https");
        }
    }

    private static CommitInfo info(RevCommit c) {
        return new CommitInfo(c.abbreviate(10).name(), c.getAuthorIdent().getWhenAsInstant().toString(),
                c.getAuthorIdent().getName(), c.getShortMessage());
    }
}
