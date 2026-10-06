package com.srividhya.bankrca.source;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param mode stub (a JSON file) or git
 * @param stubFile the JSON file describing the sample source, for stub mode
 * @param remoteUrl git mode, one repository: the remote to mirror; or leave empty and set repoDir to an existing clone
 * @param username token credentials for the remotes; the token is read from the environment only
 * @param reposDir where mirrors are kept, one folder per repository
 * @param deployedRef branch, tag or commit that production runs, unless a repository names its own
 * @param repositories git mode, several repositories: one entry each
 */
@ConfigurationProperties("rca.source")
public record SourceProperties(String mode, String stubFile, String remoteUrl, String username, String token,
        Path repoDir, Path reposDir, String deployedRef, Duration fetchInterval, List<Repo> repositories) {

    /**
     * @param name short name, shown in results and used as the mirror's folder
     * @param remoteUrl the remote to mirror (https), or
     * @param path an existing clone to read as it is
     * @param deployedRef what production runs for this repository; the common setting when left out
     * @param components the containers whose code lives here ('*' is a wildcard); every component when left out
     */
    public record Repo(String name, String remoteUrl, Path path, String deployedRef, List<String> components) {
    }

    /** Never prints the token, whoever logs this record. */
    @Override
    public String toString() {
        return "SourceProperties[mode=" + mode + ", remoteUrl=" + remoteUrl + ", repositories=" + repositories
                + ", token=<redacted>]";
    }
}
