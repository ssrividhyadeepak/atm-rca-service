package com.srividhya.bankrca.correlation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import com.srividhya.bankrca.correlation.CorrelationResult.ComponentChain;
import com.srividhya.bankrca.correlation.CorrelationResult.FailureSignature;
import com.srividhya.bankrca.correlation.CorrelationResult.IdCount;
import com.srividhya.bankrca.failure.FailedTransaction;
import com.srividhya.bankrca.failure.FailureBatch;
import com.srividhya.bankrca.failure.FailureRetrievalService;

/**
 * Turns a window of failure events into a short list of distinct problems, with no LLM:
 * groups by signature, then describes each group by how often, when, and how widely it
 * happens, and finds requests that failed in more than one component.
 */
@Service
public class CorrelationService {

    private static final int MAX_LISTED = 5;
    private static final int MAX_CHAINS = 20;
    private static final int MAX_MESSAGE_CHARS = 300;
    private static final int FEW = 5;
    /** BURST: the middle 90% of the events fall within this share of the window. */
    private static final double BURST_SHARE = 0.25;
    /** STEADY: they stretch over at least this share of the window. */
    private static final double STEADY_SHARE = 0.5;
    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private final FailureRetrievalService failures;

    public CorrelationService(FailureRetrievalService failures) {
        this.failures = failures;
    }

    public CorrelationResult correlate(Instant from, Instant to) {
        return correlate(failures.retrieve(from, to));
    }

    public CorrelationResult correlate(FailureBatch batch) {
        Map<String, List<FailedTransaction>> groups = new LinkedHashMap<>();
        Map<String, Integer> perComponent = new LinkedHashMap<>();
        for (FailedTransaction f : batch.items()) {
            groups.computeIfAbsent(key(f), k -> new ArrayList<>()).add(f);
            perComponent.merge(component(f), 1, Integer::sum);
        }
        Duration window = Duration.between(Instant.parse(batch.from()), Instant.parse(batch.to()));

        List<FailureSignature> signatures = new ArrayList<>();
        Map<FailedTransaction, String> signatureOf = new java.util.IdentityHashMap<>();
        for (Map.Entry<String, List<FailedTransaction>> group : groups.entrySet()) {
            FailureSignature signature = signature(group.getKey(), group.getValue(), batch.total(),
                    perComponent.get(component(group.getValue().get(0))), window);
            signatures.add(signature);
            group.getValue().forEach(f -> signatureOf.put(f, signature.id()));
        }
        signatures.sort(Comparator.comparingInt(FailureSignature::count).reversed()
                .thenComparing(FailureSignature::component).thenComparing(FailureSignature::normalizedMessage));

        return new CorrelationResult(batch.from(), batch.to(), batch.source(), batch.total(), batch.truncated(),
                batch.unparsed(), batch.byComponent(), signatures, chains(batch.items(), signatureOf));
    }

    // ---- grouping -----------------------------------------------------------------------------

    private static String key(FailedTransaction f) {
        return component(f) + "|" + (f.exception() == null ? "-" : f.exception()) + "|" + normalize(f);
    }

    private static String component(FailedTransaction f) {
        return f.component() == null ? "unknown" : f.component();
    }

    /**
     * The message with everything that differs between two occurrences of the same problem
     * replaced by a placeholder: this event's own ids, other ids, masked values and numbers.
     */
    static String normalize(FailedTransaction f) {
        String m = f.message() == null ? "" : f.message();
        m = replaceValue(m, f.traceId(), "<traceId>");
        m = replaceValue(m, f.sessionId(), "<sessionId>");
        m = replaceValue(m, f.bankId(), "<bankId>");
        m = m.replaceAll(UUID, "<id>")
                .replaceAll("\\*{2,}\\w*", "<masked>")
                .replaceAll("\\b(?=[0-9a-fA-F]*\\d)[0-9a-fA-F]{12,}\\b", "<id>")
                .replaceAll("\\d+", "<n>")
                .replaceAll("\\s+", " ").strip();
        return m.length() <= MAX_MESSAGE_CHARS ? m : m.substring(0, MAX_MESSAGE_CHARS);
    }

    private static String replaceValue(String text, String value, String placeholder) {
        return value == null || value.isBlank() ? text : text.replace(value, placeholder);
    }

    // ---- one signature ------------------------------------------------------------------------

    private static FailureSignature signature(String key, List<FailedTransaction> events, int total,
            int componentTotal, Duration window) {
        FailedTransaction first = events.get(0);
        List<Instant> times = events.stream().map(f -> time(f.timestamp())).filter(t -> t != null).sorted().toList();

        Map<String, Integer> pods = counts(events.stream().map(FailedTransaction::pod).toList());
        Map<String, Integer> banks = counts(events.stream().map(FailedTransaction::bankId).toList());
        Map<Instant, Integer> hours = new TreeMap<>();
        times.forEach(t -> hours.merge(t.truncatedTo(ChronoUnit.HOURS), 1, Integer::sum));
        Map.Entry<Instant, Integer> peak = hours.entrySet().stream()
                .max(Map.Entry.<Instant, Integer>comparingByValue()).orElse(null);

        List<String> traceIds = events.stream().map(FailedTransaction::traceId).filter(t -> t != null).toList();
        List<String> sessionIds = events.stream().map(FailedTransaction::sessionId).filter(s -> s != null).toList();
        return new FailureSignature(id(key), first.transaction(), component(first), first.exception(),
                mostCommon(events.stream().map(FailedTransaction::level).toList()),
                mostCommon(events.stream().map(FailedTransaction::logger).toList()),
                normalize(first), first.message(), events.size(), percent(events.size(), componentTotal),
                percent(events.size(), total),
                times.isEmpty() ? null : times.get(0).toString(),
                times.isEmpty() ? null : times.get(times.size() - 1).toString(),
                timePattern(times, window),
                peak == null ? null : peak.getKey().toString(), peak == null ? 0 : peak.getValue(),
                pods.size(), pods.keySet().stream().limit(MAX_LISTED).toList(),
                banks.size(), banks.entrySet().stream().limit(MAX_LISTED)
                        .map(e -> new IdCount(e.getKey(), e.getValue())).toList(),
                spread(traceIds), spread(sessionIds),
                events.stream().map(FailedTransaction::stackTrace).filter(s -> s != null).findFirst().orElse(null));
    }

    /**
     * Looks at where the middle 90% of the events fall, so one stray event does not hide a burst.
     */
    static String timePattern(List<Instant> sortedTimes, Duration window) {
        int n = sortedTimes.size();
        if (n < FEW) {
            return "FEW";
        }
        Instant low = sortedTimes.get((int) Math.floor(0.05 * (n - 1)));
        Instant high = sortedTimes.get((int) Math.ceil(0.95 * (n - 1)));
        double share = (double) Duration.between(low, high).toMillis() / Math.max(1, window.toMillis());
        return share <= BURST_SHARE ? "BURST" : share >= STEADY_SHARE ? "STEADY" : "SCATTERED";
    }

    /** Values with how often each occurs, most frequent first; nulls are left out. */
    private static Map<String, Integer> counts(List<String> values) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        values.stream().filter(v -> v != null).forEach(v -> counts.merge(v, 1, Integer::sum));
        Map<String, Integer> sorted = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    private static String mostCommon(List<String> values) {
        return counts(values).keySet().stream().findFirst().orElse(null);
    }

    /** Up to three of a list: the first, the middle and the last. */
    private static List<String> spread(List<String> values) {
        if (values.size() <= 3) {
            return values;
        }
        return List.of(values.get(0), values.get(values.size() / 2), values.get(values.size() - 1));
    }

    private static double percent(int part, int total) {
        return total == 0 ? 0 : Math.round(part * 1000.0 / total) / 10.0;
    }

    private static Instant time(String timestamp) {
        try {
            return timestamp == null ? null : Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Ten hex characters of a hash of the grouping key: short to read, stable across days. */
    private static String id(String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 5);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- requests that failed in more than one component ----------------------------------------

    private static List<ComponentChain> chains(List<FailedTransaction> events,
            Map<FailedTransaction, String> signatureOf) {
        Map<String, List<FailedTransaction>> byTrace = new LinkedHashMap<>();
        events.stream().filter(f -> f.traceId() != null)
                .forEach(f -> byTrace.computeIfAbsent(f.traceId(), k -> new ArrayList<>()).add(f));

        record Chain(Set<String> signatureIds, List<String> traceIds) {
        }
        Map<List<String>, Chain> chains = new LinkedHashMap<>();
        for (Map.Entry<String, List<FailedTransaction>> trace : byTrace.entrySet()) {
            List<FailedTransaction> ordered = new ArrayList<>(trace.getValue());
            ordered.sort(Comparator.comparing(f -> f.timestamp() == null ? "" : f.timestamp()));
            List<String> path = new ArrayList<>(new LinkedHashSet<>(ordered.stream().map(CorrelationService::component).toList()));
            if (path.size() < 2) {
                continue;
            }
            Chain chain = chains.computeIfAbsent(path, k -> new Chain(new LinkedHashSet<>(), new ArrayList<>()));
            ordered.forEach(f -> chain.signatureIds().add(signatureOf.get(f)));
            chain.traceIds().add(trace.getKey());
        }
        return chains.entrySet().stream()
                .map(e -> new ComponentChain(e.getKey(), e.getValue().traceIds().size(),
                        List.copyOf(e.getValue().signatureIds()), spread(e.getValue().traceIds())))
                .sorted(Comparator.comparingInt(ComponentChain::traces).reversed())
                .limit(MAX_CHAINS)
                .toList();
    }
}
