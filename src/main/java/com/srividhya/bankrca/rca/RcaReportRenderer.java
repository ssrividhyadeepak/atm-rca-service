package com.srividhya.bankrca.rca;

import com.srividhya.bankrca.rca.RcaReport.Finding;

/** The report as markdown: plain string building, every line from the report's own data. */
final class RcaReportRenderer {

    private RcaReportRenderer() {
    }

    static String render(RcaReport r) {
        StringBuilder md = new StringBuilder();
        md.append("# Failure RCA: ").append(r.from()).append(" to ").append(r.to()).append("\n\n");
        md.append(r.headline()).append("\n\n");
        md.append("Generated ").append(r.generatedAt()).append(" by ").append(r.analyzer()).append(" from ")
                .append(r.source()).append(". Built by fixed rules, without an LLM.");
        if (r.truncated()) {
            md.append(" The search hit its row cap, so counts may be too low.");
        }
        if (r.inputHash() != null) {
            md.append(" Input: daily-rca-input-").append(r.id()).append(".json, schema ")
                    .append(r.inputSchemaVersion()).append(", sha256 ").append(r.inputHash(), 0, 12).append('.');
        }
        md.append("\n\n");
        if (r.findings().isEmpty()) {
            return md.toString();
        }

        md.append("| # | Severity | Status | Category | Component | Problem | Events | Pattern |\n");
        md.append("|---|---|---|---|---|---|---|---|\n");
        for (Finding f : r.findings()) {
            md.append("| ").append(f.rank()).append(" | ").append(f.severity()).append(" | ").append(f.status())
                    .append(" | ").append(f.category()).append(" | ").append(f.component()).append(" | ")
                    .append(f.exception() == null ? "(no exception class)" : "`" + simple(f.exception()) + "`")
                    .append(" | ").append(f.count()).append(" | ").append(f.timePattern()).append(" |\n");
        }
        md.append('\n');

        for (Finding f : r.findings()) {
            md.append("## ").append(f.rank()).append(". ").append(f.title()).append("\n\n");
            md.append("- Severity ").append(f.severity()).append(", ").append(f.status());
            if (f.knownSince() != null) {
                md.append(" (first seen ").append(f.knownSince()).append(")");
            }
            md.append(", category ").append(f.category()).append(", confidence ").append(f.confidence()).append('\n');
            md.append("- Likely cause: ").append(f.likelyCause()).append('\n');
            md.append("- Next step: ").append(f.suggestedAction()).append('\n');
            md.append("- Evidence:\n");
            f.evidence().forEach(e -> md.append("  - ").append(e).append('\n'));
            md.append("- Signature ").append(f.signatureId()).append("; rules ").append(String.join(", ", f.rules()))
                    .append("\n\n");
        }
        return md.toString();
    }

    private static String simple(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}
