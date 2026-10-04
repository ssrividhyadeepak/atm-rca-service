package com.srividhya.atmrca.failure;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.srividhya.atmrca.config.RcaProperties;
import com.srividhya.atmrca.failure.FailureBatch.TransactionCount;
import com.srividhya.atmrca.security.PiiMasker;
import com.srividhya.atmrca.splunk.SplunkClient;

/** Retrieves the failed transactions of the monitored transaction types and masks them on the way in. */
@Service
public class FailureRetrievalService {

    private final SplunkClient splunk;
    private final PiiMasker masker;
    private final List<String> transactions;
    private final int maxRows;

    public FailureRetrievalService(SplunkClient splunk, PiiMasker masker, RcaProperties props) {
        this.splunk = splunk;
        this.masker = masker;
        this.transactions = props.monitor().transactions();
        this.maxRows = props.splunk().maxRows();
    }

    public FailureBatch retrieve(Instant from, Instant to) {
        List<FailedTransaction> items = splunk
                .search(SplunkClient.FAILED_TRANSACTIONS, from, to, Map.of("transactions", transactions, "limit", maxRows))
                .stream().map(this::toFailure).toList();

        Map<String, Integer> counts = new LinkedHashMap<>();
        items.forEach(f -> counts.merge(f.transaction(), 1, Integer::sum));
        List<TransactionCount> byTransaction = counts.entrySet().stream()
                .map(e -> new TransactionCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(TransactionCount::count).reversed()
                        .thenComparing(TransactionCount::transaction))
                .toList();
        return new FailureBatch(from.toString(), to.toString(), splunk.description(), items.size(),
                items.size() >= maxRows, byTransaction, items);
    }

    private FailedTransaction toFailure(Map<String, Object> row) {
        return new FailedTransaction(text(row, "ts"), text(row, "transaction"), text(row, "service"),
                text(row, "correlationId"), text(row, "terminal"), text(row, "exception"),
                masker.mask(text(row, "message")), masker.mask(text(row, "stackTrace")));
    }

    /** Missing and empty fields are both null. */
    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }
}
