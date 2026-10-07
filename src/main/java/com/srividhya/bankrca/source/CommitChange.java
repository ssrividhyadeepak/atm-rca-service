package com.srividhya.bankrca.source;

import java.util.List;

/**
 * One commit on the deployed ref and what it touched.
 *
 * @param time when it was committed to the branch (the committer's time), ISO-8601 UTC
 * @param files paths it changed, at most 30; filesChanged is the full count
 */
public record CommitChange(String repository, String ref, String hash, String time, String author, String message,
        List<String> files, int filesChanged) {

    public static final int MAX_FILES = 30;
}
