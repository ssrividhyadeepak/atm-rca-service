package com.srividhya.bankrca.rca;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reading and shortening Java stack traces. */
public final class StackTraces {

    // "at com.acme.Foo.bar(Foo.java:12)", with an optional module prefix such as "java.base/"
    private static final Pattern FRAME = Pattern.compile("^\\s*at\\s+(?:[^\\s/()]+/)*([\\w.$]+\\([^)]*\\))");
    private static final Pattern CAUSED_BY = Pattern.compile("^Caused by:\\s*([\\w.$]+)");
    private static final List<String> FRAMEWORK_PACKAGES = List.of("java.", "javax.", "jakarta.", "jdk.", "sun.",
            "com.sun.", "org.springframework.", "org.apache.", "io.netty.", "reactor.", "io.github.resilience4j.");

    private StackTraces() {
    }

    /** The class after the last "Caused by:", or null when the trace has none. */
    public static String rootCause(String stackTrace) {
        String rootCause = null;
        if (stackTrace != null) {
            for (String line : stackTrace.split("\n")) {
                Matcher m = CAUSED_BY.matcher(line.strip());
                if (m.find()) {
                    rootCause = m.group(1);
                }
            }
        }
        return rootCause;
    }

    /**
     * One stack frame.
     *
     * @param className outer class, without inner-class or lambda suffixes
     * @param file source file name from the frame, or null (native or generated frames)
     * @param line null when the frame has no line number
     */
    public record Frame(String className, String method, String file, Integer line) {
    }

    private static final Pattern FRAME_PARTS = Pattern.compile("^([\\w.$]+)\\.([\\w$<>]+)\\(([^:)]*)(?::(\\d+))?\\)$");

    /** {@link #location} taken apart, or null when the trace has no application frame. */
    public static Frame locationFrame(String stackTrace) {
        String location = location(stackTrace);
        if (location == null) {
            return null;
        }
        Matcher m = FRAME_PARTS.matcher(location);
        if (!m.matches()) {
            return null;
        }
        String className = m.group(1);
        int dollar = className.indexOf('$');
        String file = m.group(3).contains(".") ? m.group(3) : null;
        return new Frame(dollar > 0 ? className.substring(0, dollar) : className, m.group(2), file,
                m.group(4) == null ? null : Integer.valueOf(m.group(4)));
    }

    /** The first application frame of the root cause: where our code was when it went wrong. */
    public static String location(String stackTrace) {
        if (stackTrace == null) {
            return null;
        }
        String firstOverall = null;
        String firstAfterLastCause = null;
        for (String line : stackTrace.split("\n")) {
            if (CAUSED_BY.matcher(line.strip()).find()) {
                firstAfterLastCause = null;
                continue;
            }
            Matcher m = FRAME.matcher(line);
            if (m.find() && isApplication(m.group(1))) {
                if (firstOverall == null) {
                    firstOverall = m.group(1);
                }
                if (firstAfterLastCause == null) {
                    firstAfterLastCause = m.group(1);
                }
            }
        }
        return firstAfterLastCause != null ? firstAfterLastCause : firstOverall;
    }

    /**
     * A stack trace cut down to what an analysis needs: every exception and "Caused by" line,
     * the application frames (up to {@code maxApplicationFrames} per exception), and a count
     * in place of each run of framework and JDK frames. A real trace of a few hundred lines
     * becomes a dozen, without losing where it failed or why.
     */
    public static String trim(String stackTrace, int maxApplicationFrames) {
        if (stackTrace == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        int skipped = 0;
        int kept = 0;
        for (String line : stackTrace.split("\n")) {
            Matcher m = FRAME.matcher(line);
            if (!m.find()) {
                // An exception line, "Caused by:" or "... 12 more": starts a new section
                skipped = flush(out, skipped);
                if (!line.isBlank()) {
                    out.add(line.stripTrailing());
                }
                if (CAUSED_BY.matcher(line.strip()).find()) {
                    kept = 0;
                }
            } else if (isApplication(m.group(1)) && kept < maxApplicationFrames) {
                skipped = flush(out, skipped);
                out.add("\tat " + m.group(1));
                kept++;
            } else {
                skipped++;
            }
        }
        flush(out, skipped);
        return String.join("\n", out);
    }

    private static int flush(List<String> out, int skipped) {
        if (skipped > 0) {
            out.add("\t... " + skipped + " other frame" + (skipped == 1 ? "" : "s"));
        }
        return 0;
    }

    private static boolean isApplication(String frame) {
        return FRAMEWORK_PACKAGES.stream().noneMatch(frame::startsWith);
    }
}
