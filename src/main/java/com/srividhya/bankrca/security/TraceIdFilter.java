package com.srividhya.bankrca.security;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Traceability: every HTTP request gets a trace id, taken from the caller's W3C traceparent
 * header when it sends one. It is on every log line and audit entry written while the
 * request is handled, and is returned in the X-Trace-Id response header.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "traceId";
    public static final String RESPONSE_HEADER = "X-Trace-Id";
    // version-traceid-parentid-flags; only a well-formed header is trusted
    private static final Pattern TRACEPARENT = Pattern.compile("[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("traceparent");
        Matcher m = header == null ? null : TRACEPARENT.matcher(header.strip());
        String traceId = m != null && m.matches() ? m.group(1) : UUID.randomUUID().toString().replace("-", "");
        MDC.put(MDC_KEY, traceId);
        response.setHeader(RESPONSE_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
