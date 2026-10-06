package com.srividhya.bankrca.source;

import java.util.List;

/**
 * Where a stack frame is in the deployed code, and who changed it last.
 *
 * @param found false when the class or line could not be found; note then says why
 * @param repository which configured repository it is in
 * @param ref the branch, tag or commit the lookup was made against (what production runs)
 * @param path file path inside the repository
 * @param code the line itself
 * @param snippet a few lines around it, with line numbers; the line starts with '>'
 * @param lineLastChanged the commit that last changed that line (git blame)
 * @param fileLastChanged the latest commit to the file; the cause is often near the failing line, not on it
 */
public record SourceLocation(
        boolean found,
        String note,
        String repository,
        String ref,
        String className,
        String method,
        String path,
        Integer line,
        String code,
        String snippet,
        CommitInfo lineLastChanged,
        CommitInfo fileLastChanged,
        List<CommitInfo> recentCommits) {

    public record CommitInfo(String hash, String time, String author, String message) {
    }

    public static SourceLocation notFound(String className, String method, Integer line, String note) {
        return new SourceLocation(false, note, null, null, className, method, null, line, null, null, null, null,
                List.of());
    }
}
