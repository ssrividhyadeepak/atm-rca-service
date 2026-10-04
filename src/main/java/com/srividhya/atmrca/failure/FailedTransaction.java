package com.srividhya.atmrca.failure;

/**
 * One failed transaction as retrieved from Splunk, with card numbers, account numbers and
 * emails already masked. terminal and stackTrace may be null.
 */
public record FailedTransaction(
        String timestamp,
        String transaction,
        String service,
        String correlationId,
        String terminal,
        String exception,
        String message,
        String stackTrace) {
}
