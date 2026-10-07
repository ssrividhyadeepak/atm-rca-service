package com.srividhya.bankrca.source;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.srividhya.bankrca.security.PiiMasker;

/**
 * "Which commit could have caused this?": the commits that reached the deployed ref before
 * the failures started, ranked by fixed rules - how close in time, whether they touch the
 * failing class, and what kind of files they change. The score orders the list and each
 * suspect says why it scored; it is a ranking of where to look, not a finding of guilt.
 */
@Service
public class SuspectService {

    /**
     * @param score 0-100; see reasons for how it was reached
     * @param kinds what it changed: CODE, CONFIG, BUILD, TEST, DOCS
     * @param pullRequest changeNumber read from the commit message when it names one, e.g. "#412", "CHG0030101"
     */
    public record Suspect(int rank, int score, String hash, String time, long minutesBeforeFailure, String author,
            String message, String pullRequest, String changeNumber, String repository, List<String> kinds,
            List<String> files, int filesChanged, List<String> reasons) {
    }

    /** @param considered how many commits were in the window, of which suspects are the highest ranked */
    public record SuspectSearch(String component, String failureTime, String from, String failingClass, String source,
            int considered, List<Suspect> suspects, String note) {
    }

    public static final int MAX_SUSPECTS = 10;
    private static final int MAX_COMMITS = 200;
    private static final Pattern COMPONENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*");
    private static final Pattern PULL_REQUEST = Pattern.compile("(?i)(?:pull request |\\(|PR ?)#(\\d+)");
    private static final Pattern CHANGE_NUMBER = Pattern.compile("\\bCHG\\d{5,}\\b");
    private static final Pattern CONFIG = Pattern.compile(
            "(?i).*\\.(ya?ml|properties|conf|toml|env|json|xml|tf)$|.*(^|/)(Dockerfile|values[^/]*|configmap[^/]*)$");
    private static final Pattern BUILD = Pattern.compile("(?i).*(^|/)(pom\\.xml|build\\.gradle(\\.kts)?|settings\\.gradle|gradle\\.properties|package(-lock)?\\.json)$");
    private static final Pattern DOCS = Pattern.compile("(?i).*\\.(md|txt|adoc|rst|png|jpg|svg)$");
    private static final Pattern TEST = Pattern.compile("(?i).*(^|/)(src/test/|test/|tests/).*");

    private final SourceRepository source;
    private final PiiMasker masker;

    public SuspectService(SourceRepository source, PiiMasker masker) {
        this.source = source;
        this.masker = masker;
    }

    /**
     * @param failingClass fully qualified class from the stack trace, or null
     * @throws IllegalArgumentException when component or failingClass is not a plain name
     */
    public SuspectSearch find(String component, Instant failureTime, Duration lookback, String failingClass) {
        if (component == null || !COMPONENT.matcher(component).matches()) {
            throw new IllegalArgumentException("'component' must be a component name such as withdrawal-p1");
        }
        if (failingClass != null && !CLASS_NAME.matcher(failingClass).matches()) {
            throw new IllegalArgumentException("'failingClass' must be a class name such as com.example.bank.Foo");
        }
        Instant from = failureTime.minus(lookback);
        List<CommitChange> commits = source.commits(component, from, failureTime, MAX_COMMITS);
        String classPath = failingClass == null ? null : "/" + failingClass.replace('.', '/');

        List<Suspect> scored = new ArrayList<>();
        for (CommitChange c : commits) {
            long minutes = Duration.between(Instant.parse(c.time()), failureTime).toMinutes();
            List<String> reasons = new ArrayList<>();
            int score = timeScore(minutes);
            reasons.add("committed " + span(minutes) + " before the failures began (+" + score + ")");

            Set<String> kinds = new LinkedHashSet<>();
            boolean touchesClass = false;
            for (String file : c.files()) {
                kinds.add(kind(file));
                // Foo.java, Foo.kt ... and not FooTest
                touchesClass |= classPath != null && ("/" + file).matches(".*" + Pattern.quote(classPath) + "\\.\\w+$");
            }
            if (touchesClass) {
                score += 35;
                reasons.add("changes the failing class " + failingClass.substring(failingClass.lastIndexOf('.') + 1) + " (+35)");
            }
            if (kinds.contains("CODE") || kinds.contains("CONFIG") || kinds.contains("BUILD")) {
                score += 15;
                reasons.add("changes what runs: " + String.join(", ", kinds.stream()
                        .filter(k -> !k.equals("TEST") && !k.equals("DOCS")).toList()) + " (+15)");
            } else if (!kinds.isEmpty()) {
                reasons.add("only " + String.join(" and ", kinds).toLowerCase() + ": cannot change behaviour (+0)");
            }
            String message = masker.mask(c.message());
            Matcher pr = PULL_REQUEST.matcher(c.message() == null ? "" : c.message());
            Matcher chg = CHANGE_NUMBER.matcher(c.message() == null ? "" : c.message());
            scored.add(new Suspect(0, score, c.hash(), c.time(), minutes, c.author(), message,
                    pr.find() ? "#" + pr.group(1) : null, chg.find() ? chg.group() : null, c.repository(),
                    List.copyOf(kinds), c.files(), c.filesChanged(), reasons));
        }
        scored.sort(Comparator.comparingInt(Suspect::score).reversed().thenComparingLong(Suspect::minutesBeforeFailure));
        List<Suspect> ranked = new ArrayList<>();
        for (Suspect s : scored.subList(0, Math.min(MAX_SUSPECTS, scored.size()))) {
            ranked.add(new Suspect(ranked.size() + 1, s.score(), s.hash(), s.time(), s.minutesBeforeFailure(), s.author(),
                    s.message(), s.pullRequest(), s.changeNumber(), s.repository(), s.kinds(), s.files(), s.filesChanged(),
                    s.reasons()));
        }
        return new SuspectSearch(component, failureTime.toString(), from.toString(), failingClass, source.description(),
                commits.size(), ranked,
                "Commit time is when a commit reached the deployed branch, not when it was deployed: confirm the "
                        + "deployment with serviceNowRecentChanges. Commits after the failures began are not listed.");
    }

    private static int timeScore(long minutes) {
        return minutes <= 60 ? 50 : minutes <= 360 ? 40 : minutes <= 1440 ? 30 : minutes <= 4320 ? 18
                : minutes <= 10080 ? 8 : 3;
    }

    static String kind(String path) {
        if (TEST.matcher(path).matches()) {
            return "TEST";
        }
        if (BUILD.matcher(path).matches()) {
            return "BUILD";
        }
        if (DOCS.matcher(path).matches()) {
            return "DOCS";
        }
        return CONFIG.matcher(path).matches() ? "CONFIG" : "CODE";
    }

    private static String span(long minutes) {
        return minutes < 120 ? minutes + " min" : minutes < 2880 ? (minutes / 60) + "h" : (minutes / 1440) + " days";
    }
}
