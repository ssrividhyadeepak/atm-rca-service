package com.srividhya.bankrca.knowledge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.srividhya.bankrca.knowledge.KnowledgeIndex.Passage;
import com.srividhya.bankrca.knowledge.KnowledgeIndex.Scored;
import com.srividhya.bankrca.rca.RcaReport;
import com.srividhya.bankrca.rca.RcaReport.Finding;
import com.srividhya.bankrca.security.PiiMasker;

import jakarta.annotation.PostConstruct;

/**
 * The knowledge base: runbooks and past RCAs. Markdown files in the knowledge folder
 * (runbooks/ and past-rcas/) plus the findings of every RCA report this service has saved,
 * so the history grows by itself.
 *
 * Retrieval has a deterministic first step: a document written for the same exception class
 * is an EXACT match whatever its similarity score; similarity search fills in when there is
 * none, and is reported as SEMANTIC with its score.
 */
@Service
public class KnowledgeService {

    public static final String RUNBOOK = "runbook";
    public static final String PAST_RCA = "past-rca";

    private record Doc(String id, String type, String title, Map<String, String> metadata, String content,
            Set<String> exceptions, String day) {
    }

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);
    private static final Pattern HEADER = Pattern.compile("^- ([A-Za-z ]+):\\s*(.*)$");
    private static final Pattern EXCEPTION_CLASS = Pattern.compile("\\b([A-Z]\\w*(?:Exception|Error))\\b");
    private static final int SUMMARY_CHARS = 400;

    private final KnowledgeIndex index;
    private final PiiMasker masker;
    private final Path dir;
    private final Map<String, Doc> docs = new LinkedHashMap<>();

    public KnowledgeService(KnowledgeIndex index, PiiMasker masker, @Value("${rca.knowledge.dir}") Path dir) {
        this.index = index;
        this.masker = masker;
        this.dir = dir;
    }

    @PostConstruct
    public synchronized void reload() {
        docs.keySet().removeIf(id -> !id.startsWith("report:"));
        List<Doc> loaded = new ArrayList<>();
        loaded.addAll(load(dir.resolve("runbooks"), RUNBOOK));
        loaded.addAll(load(dir.resolve("past-rcas"), PAST_RCA));
        List<Doc> reports = new ArrayList<>(docs.values());
        index.clear();
        loaded.forEach(d -> docs.put(d.id(), d));
        loaded.forEach(d -> index.put(passages(d)));
        reports.forEach(d -> index.put(passages(d)));
        log.info("Knowledge: {} runbooks and {} past RCAs from {}, {} search", count(RUNBOOK), count(PAST_RCA), dir,
                index.mode());
    }

    public String description() {
        return count(RUNBOOK) + " runbooks and " + count(PAST_RCA) + " past RCAs from " + dir + ", searched by "
                + index.description();
    }

    public String mode() {
        return index.mode();
    }

    public synchronized long count(String type) {
        return docs.values().stream().filter(d -> d.type().equals(type)).count();
    }

    /** Makes the findings of a saved report part of the history that later days are compared with. */
    public synchronized void remember(RcaReport report) {
        for (Finding f : report.findings()) {
            if (f.category().equals("PROPAGATED")) {
                continue;
            }
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("id", "RCA-" + report.id() + "-" + f.signatureId());
            meta.put("date", report.id());
            meta.put("component", f.component());
            if (f.exception() != null) {
                meta.put("exception", simple(f.exception()));
            }
            meta.put("root_cause", f.likelyCause());
            meta.put("resolution", f.suggestedAction());
            String content = "# " + f.title() + "\n" + f.category() + ", " + f.count() + " events, " + f.timePattern()
                    + ".\n" + f.likelyCause() + "\n" + f.suggestedAction();
            Doc d = new Doc("report:" + report.id() + ":" + f.signatureId(), PAST_RCA, f.title(), meta, content,
                    f.exception() == null ? Set.of() : Set.of(simple(f.exception())), report.id());
            docs.put(d.id(), d);
            index.put(passages(d));
        }
    }

    /**
     * @param query what to look for: free text, or an exception signature
     * @param beforeDay when not null, past RCAs dated on or after this day are left out, so a
     *        day's analysis is not matched against its own earlier run
     */
    public synchronized List<KnowledgeHit> search(String query, String type, int limit, String beforeDay) {
        String masked = masker.mask(query);
        Set<String> exceptions = new LinkedHashSet<>();
        Matcher m = EXCEPTION_CLASS.matcher(masked);
        while (m.find()) {
            exceptions.add(m.group(1));
        }

        Map<String, KnowledgeHit> hits = new LinkedHashMap<>();
        // 1. Exact: written for this exception class. Newest first for past RCAs.
        docs.values().stream().filter(d -> d.type().equals(type) && allowed(d, beforeDay))
                .filter(d -> d.exceptions().stream().anyMatch(exceptions::contains))
                .sorted((a, b) -> String.valueOf(b.day()).compareTo(String.valueOf(a.day())))
                .forEach(d -> hits.put(d.id(), hit(d, "EXACT", 1.0, true, null)));
        // 2. Semantic: scored by the best passage of each document
        for (Scored s : index.search(masked, type, 50)) {
            Doc d = docs.get(s.passage().docId());
            if (d != null && allowed(d, beforeDay) && !hits.containsKey(d.id())) {
                hits.put(d.id(), hit(d, "SEMANTIC", s.score(), s.score() >= index.relatedThreshold(), s.passage().text()));
            }
        }
        return hits.values().stream().limit(limit).toList();
    }

    private static boolean allowed(Doc d, String beforeDay) {
        return beforeDay == null || d.day() == null || d.day().compareTo(beforeDay) < 0;
    }

    private KnowledgeHit hit(Doc d, String matchedBy, double score, boolean related, String passage) {
        return new KnowledgeHit(d.metadata().getOrDefault("id", d.id()), d.type(), d.title(), matchedBy, score, related,
                passage, summary(d), d.metadata());
    }

    /** A runbook's mitigation; a past RCA's root cause and resolution. */
    private static String summary(Doc d) {
        String text;
        if (d.type().equals(RUNBOOK)) {
            int at = d.content().indexOf("## Mitigation");
            text = at < 0 ? d.content() : d.content().substring(at + "## Mitigation".length());
        } else {
            text = "Root cause: " + d.metadata().getOrDefault("root_cause", "not recorded") + " Resolution: "
                    + d.metadata().getOrDefault("resolution", "not recorded");
        }
        text = text.replaceAll("\\s+", " ").strip();
        return text.length() <= SUMMARY_CHARS ? text : text.substring(0, SUMMARY_CHARS) + "...";
    }

    /**
     * A document is split so that each passage is about one thing: the title with the
     * "- Key: value" lines, then each "##" section, every one prefixed with the title. One
     * vector for a whole document blurs its sections together.
     */
    private static List<Passage> passages(Doc d) {
        List<Passage> out = new ArrayList<>();
        StringBuilder head = new StringBuilder(d.title()).append(". ");
        d.metadata().forEach((k, v) -> {
            if (!k.equals("id") && !k.equals("owner") && !k.equals("date") && !v.isBlank()) {
                head.append(k.replace('_', ' ')).append(": ").append(v).append(". ");
            }
        });
        out.add(new Passage(d.id() + "#0", d.id(), d.type(), head.toString().strip()));
        String[] sections = d.content().split("(?m)^## ");
        for (int i = 1; i < sections.length; i++) {
            String body = sections[i].replaceAll("\\s+", " ").strip();
            if (!body.isBlank()) {
                out.add(new Passage(d.id() + "#" + i, d.id(), d.type(), d.title() + ". " + body));
            }
        }
        return out;
    }

    private static List<Doc> load(Path folder, String type) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            List<Doc> loaded = new ArrayList<>();
            for (Path f : files.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                String text = Files.readString(f);
                String title = text.lines().findFirst().orElse("").replaceFirst("^#+\\s*", "");
                Map<String, String> meta = new LinkedHashMap<>();
                for (String line : text.lines().toList()) {
                    Matcher m = HEADER.matcher(line);
                    if (m.matches()) {
                        meta.put(m.group(1).strip().toLowerCase().replace(' ', '_'), m.group(2).strip());
                    }
                }
                Set<String> exceptions = new LinkedHashSet<>();
                for (String key : List.of("exceptions", "exception")) {
                    for (String e : meta.getOrDefault(key, "").split(",")) {
                        if (!e.isBlank()) {
                            exceptions.add(e.strip());
                        }
                    }
                }
                String name = f.getFileName().toString().replace(".md", "");
                loaded.add(new Doc(type + ":" + name, type, title, meta, text, exceptions, meta.get("date")));
            }
            return loaded;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String simple(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}
