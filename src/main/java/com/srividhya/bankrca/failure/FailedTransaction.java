package com.srividhya.bankrca.failure;

/**
 * One failure event from Splunk, taken apart into the fields the analysis needs. Free text
 * (message, stackTrace) has card numbers, account numbers and emails masked. Any field the
 * event did not carry is null.
 *
 * @param transaction the label configured for the component (rca.monitor.components)
 * @param component the container that logged the event (kubernetes.container_name)
 * @param pod the pod it ran in (kubernetes.pod_name)
 * @param level the application's own level, from the "LEVEL: X" part of the log line
 * @param logger the class that logged it
 * @param bankId tracing metadata: the id of the terminal or branch the request came from
 * @param traceId tracing metadata: the id that follows one request across components
 * @param sessionId tracing metadata: the customer tracking session, on UI events
 * @param exception fully qualified exception class when the line names one
 * @param message the log text without the line prefix and without the stack trace
 */
public record FailedTransaction(
        String timestamp,
        String transaction,
        String component,
        String pod,
        String namespace,
        String cluster,
        String host,
        String level,
        String logger,
        String thread,
        String bankId,
        String traceId,
        String sessionId,
        String exception,
        String message,
        String stackTrace) {
}
