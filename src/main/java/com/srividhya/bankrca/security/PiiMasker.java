package com.srividhya.bankrca.security;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Data minimization: masks card numbers (PAN), account numbers and emails BEFORE any text
 * leaves this server and reaches the LLM. Applied to logs, diffs and audit entries.
 */
@Component
public class PiiMasker {

    // 13-19 digit card numbers, optionally separated by spaces or dashes
    private static final Pattern PAN = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");
    private static final Pattern ACCOUNT = Pattern.compile("(?i)(acct|account)(=|:\\s*)(\\d{4,})");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");

    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = maskPan(text);
        masked = ACCOUNT.matcher(masked).replaceAll(m -> m.group(1) + m.group(2) + "****" + last4(m.group(3)));
        return EMAIL.matcher(masked).replaceAll("<email-redacted>");
    }

    private String maskPan(String text) {
        Matcher m = PAN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String digits = m.group().replaceAll("[ -]", "");
            m.appendReplacement(sb, "************" + last4(digits));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String last4(String digits) {
        return digits.length() <= 4 ? digits : digits.substring(digits.length() - 4);
    }
}
