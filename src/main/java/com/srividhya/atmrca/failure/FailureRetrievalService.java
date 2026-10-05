package com.srividhya.atmrca.failure;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.srividhya.atmrca.config.RcaProperties;
import com.srividhya.atmrca.config.RcaProperties.MonitoredComponent;
import com.srividhya.atmrca.failure.FailureBatch.ComponentCount;
import com.srividhya.atmrca.failure.FailureBatch.TransactionCount;
import com.srividhya.atmrca.failure.RawEventParser.Parsed;
import com.srividhya.atmrca.security.PiiMasker;
import com.srividhya.atmrca.splunk.SplunkClient;

/**
 * Retrieves the raw failure events of the monitored components, takes each one apart
 * (component, pod, tracing metadata, exception), labels it with the component's transaction
 * and masks its free text.
 */
@Service
public class FailureRetrievalService {

    public static final String UNCLASSIFIED = "unclassified";

    private record Rule(Pattern component, String transaction) {
    }

    private final SplunkClient splunk;
    private final RawEventParser parser;
    private final PiiMasker masker;
    private final String namespace;
    private final List<String> componentPatterns;
    private final List<Rule> rules = new ArrayList<>();
    private final int maxRows;

    public FailureRetrievalService(SplunkClient splunk, RawEventParser parser, PiiMasker masker, RcaProperties props) {
        this.splunk = splunk;
        this.parser = parser;
        this.masker = masker;
        this.namespace = props.monitor().namespace();
        this.componentPatterns = props.monitor().components().stream().map(MonitoredComponent::pattern).toList();
        for (MonitoredComponent c : props.monitor().components()) {
            rules.add(new Rule(glob(c.pattern()), c.transaction()));
        }
        this.maxRows = props.splunk().maxRows();
    }

    public FailureBatch retrieve(Instant from, Instant to) {
        List<Map<String, Object>> rows = splunk.search(SplunkClient.FAILED_TRANSACTIONS, from, to,
                Map.of("namespace", namespace, "components", componentPatterns, "limit", maxRows));
        List<FailedTransaction> items = new ArrayList<>();
        int unparsed = 0;
        for (Map<String, Object> row : rows) {
            Parsed p = parser.parse(text(row, "_raw"), text(row, "ts"));
            if (!p.structured()) {
                unparsed++;
            }
            items.add(new FailedTransaction(p.timestamp(), transaction(p.component()), p.component(), p.pod(),
                    p.namespace(), p.cluster(), p.host(), p.level(), p.logger(), p.thread(), p.atmId(), p.traceId(),
                    p.sessionId(), p.exception(), masker.mask(p.message()), masker.mask(p.stackTrace())));
        }
        return new FailureBatch(from.toString(), to.toString(), splunk.description(), items.size(),
                items.size() >= maxRows, unparsed,
                counts(items, FailedTransaction::transaction, TransactionCount::new,
                        Comparator.comparingInt(TransactionCount::count).reversed()
                                .thenComparing(TransactionCount::transaction)),
                counts(items, f -> f.component() == null ? "unknown" : f.component(), ComponentCount::new,
                        Comparator.comparingInt(ComponentCount::count).reversed()
                                .thenComparing(ComponentCount::component)),
                items);
    }

    /** The label of the first configured component pattern that matches. */
    private String transaction(String component) {
        if (component != null) {
            for (Rule rule : rules) {
                if (rule.component().matcher(component).matches()) {
                    return rule.transaction();
                }
            }
        }
        return UNCLASSIFIED;
    }

    private static <T> List<T> counts(List<FailedTransaction> items,
            java.util.function.Function<FailedTransaction, String> key, BiFunction<String, Integer, T> make,
            Comparator<T> order) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        items.forEach(f -> counts.merge(key.apply(f), 1, Integer::sum));
        return counts.entrySet().stream().map(e -> make.apply(e.getKey(), e.getValue())).sorted(order).toList();
    }

    /** "app-atm-withdrawal-*" style pattern: '*' matches any run of characters, everything else is literal. */
    static Pattern glob(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (String part : pattern.split("\\*", -1)) {
            if (regex.length() > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString());
    }

    /** Missing and empty fields are both null. */
    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }
}
