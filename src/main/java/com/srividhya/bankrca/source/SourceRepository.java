package com.srividhya.bankrca.source;

import java.time.Instant;
import java.util.List;

import com.srividhya.bankrca.rca.StackTraces.Frame;

/**
 * Read-only lookup of a stack frame in the application's source code, as deployed.
 * Two implementations, chosen by rca.source.mode: a JSON file for local runs (stub), and
 * git repositories for the prod profile (git).
 */
public interface SourceRepository {

    /** For the startup summary, e.g. "stub file config/stub-source.json" or the repositories in use. */
    String description();

    /**
     * @param component the container that logged the failure; used to pick the repository when there are several
     * @return never null: when nothing is found, a location with found=false and a note saying why
     */
    SourceLocation locate(String component, Frame frame);

    /**
     * Commits that reached the deployed ref in the window, newest first, with the files each changed.
     *
     * @param component used to pick the repositories, as in locate
     */
    default List<CommitChange> commits(String component, Instant from, Instant to, int limit) {
        return List.of();
    }
}
