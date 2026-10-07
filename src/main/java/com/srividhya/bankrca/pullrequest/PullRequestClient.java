package com.srividhya.bankrca.pullrequest;

import java.util.List;

import com.srividhya.bankrca.investigation.Investigation.Edit;

/**
 * Where a draft pull request is opened. Two implementations, chosen by rca.pull-request.mode:
 * a mock that only records what it would have opened, and GitHub.
 */
public interface PullRequestClient {

    /** @param branch the new branch; the same investigation always uses the same one */
    record Draft(String investigationId, String branch, String title, String body, List<Edit> edits) {
    }

    record Created(String number, String url, String system) {
    }

    /** For the startup summary. */
    String description();

    /**
     * Opens the pull request as a draft: it cannot be merged until a person marks it ready.
     * Asking twice for the same branch must not open two.
     *
     * @throws RuntimeException with a message safe to show, when it cannot be opened
     */
    Created open(Draft draft);
}
