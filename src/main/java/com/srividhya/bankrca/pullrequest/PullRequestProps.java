package com.srividhya.bankrca.pullrequest;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param mode mock (nothing is opened) or github
 * @param mockFile where the mock records what it would have opened
 * @param apiUrl the git host's API, e.g. https://api.github.com or https://github.yourbank.com/api/v3
 * @param repository owner/name of the repository the fix goes to
 * @param baseBranch the branch the pull request targets
 * @param token a token that may create branches and pull requests in that repository; from the environment only
 */
@ConfigurationProperties("rca.pull-request")
public record PullRequestProps(String mode, Path mockFile, String apiUrl, String repository, String baseBranch,
        String token, Duration timeout) {

    /** Never prints the token, whoever logs this record. */
    @Override
    public String toString() {
        return "PullRequestProps[mode=" + mode + ", apiUrl=" + apiUrl + ", repository=" + repository
                + ", token=<redacted>]";
    }
}
