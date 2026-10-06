package com.srividhya.bankrca.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Output guardrail: every identifier a model's answer cites - runbook and RCA ids, commit
 * hashes, source files, exception names, trace ids - must have come from a tool result or
 * from the question. It catches invented or mistyped references, which would send an engineer
 * to the wrong place. It does not check numbers or whether the reasoning is right.
 */
final class AnswerGuard {

    private static final List<Pattern> CITATIONS = List.of(
            Pattern.compile("\\bRB-\\d+\\b"),
            Pattern.compile("\\bRCA-\\d{4}-\\d{2}-\\d{2}(?:-[0-9a-f]+)?\\b"),
            Pattern.compile("\\b\\w+\\.(?:java|kt)(?::\\d+)?"),
            Pattern.compile("\\b[A-Z]\\w*(?:Exception|Error)\\b"),
            Pattern.compile("\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b"),
            // commit hashes: 7-40 hex characters with at least one digit and one letter, standing alone
            Pattern.compile("(?<![\\w-])(?=[0-9a-f]*\\d)(?=[0-9a-f]*[a-f])[0-9a-f]{7,40}(?![\\w-])"));

    private AnswerGuard() {
    }

    /** The citations in the answer that appear neither in a tool result nor in the question. */
    static List<String> ungrounded(String answer, String question, List<String> toolResults) {
        String known = question + "\n" + String.join("\n", toolResults);
        List<String> ungrounded = new ArrayList<>();
        for (Pattern p : CITATIONS) {
            Matcher m = p.matcher(answer == null ? "" : answer);
            while (m.find()) {
                String cited = m.group();
                if (!known.contains(cited) && !ungrounded.contains(cited)) {
                    ungrounded.add(cited);
                }
            }
        }
        return ungrounded;
    }
}
