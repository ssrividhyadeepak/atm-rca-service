package com.srividhya.bankrca.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Calls per minute, per client.
 *
 * @param defaultPerMinute any API call not listed below
 * @param searchPerMinute calls that run a Splunk search: failures, correlation, a monitoring run, an analysis
 * @param assistantPerMinute questions to the assistant
 * @param toolPerMinute calls of one tool, whoever makes them (the tool endpoint, the assistant, an MCP client)
 */
@ConfigurationProperties("rca.rate-limit")
public record RateLimitProps(int defaultPerMinute, int searchPerMinute, int assistantPerMinute, int toolPerMinute) {
}
