package com.srividhya.bankrca.source;

import java.io.File;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;

/**
 * Makes JGit ignore the machine's system/user git config. Keeps the demo reproducible and
 * stops JGit from shelling out to a `git` binary (on macOS that can pop up the Xcode installer).
 */
final class IsolatedGitConfig {

    private static volatile boolean installed;

    private IsolatedGitConfig() {
    }

    static synchronized void install() {
        if (installed) {
            return;
        }
        SystemReader.setInstance(new SystemReader.Delegate(SystemReader.getInstance()) {
            @Override
            public FileBasedConfig openSystemConfig(Config parent, FS fs) {
                return empty(parent, fs);
            }

            @Override
            public FileBasedConfig openUserConfig(Config parent, FS fs) {
                return empty(parent, fs);
            }
        });
        installed = true;
    }

    private static FileBasedConfig empty(Config parent, FS fs) {
        return new FileBasedConfig(parent, new File("/nonexistent-git-config"), fs) {
            @Override
            public void load() {
                // intentionally empty
            }

            @Override
            public boolean isOutdated() {
                return false;
            }
        };
    }
}
