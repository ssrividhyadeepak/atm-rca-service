package com.srividhya.bankrca.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Token bucket per client and bucket name: a limit of N allows N calls in a burst and then N
 * per minute. Protects Splunk, and the service itself, from a client stuck in a loop.
 * Buckets live in this process, so with several instances each enforces its own limit; a
 * shared store would be needed for one limit across instances.
 */
@Component
public class RateLimiter {

    private static final double NANOS_PER_MINUTE = 60e9;

    private static final class Bucket {
        double tokens;
        long lastRefill;
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** @return 0 when the call may proceed, otherwise the seconds until the next call is allowed */
    public long tryAcquire(String client, String name, int limitPerMinute) {
        long now = System.nanoTime();
        Bucket bucket = buckets.computeIfAbsent(client + "|" + name, k -> {
            Bucket b = new Bucket();
            b.tokens = limitPerMinute;
            b.lastRefill = now;
            return b;
        });
        synchronized (bucket) {
            bucket.tokens = Math.min(limitPerMinute,
                    bucket.tokens + (now - bucket.lastRefill) * limitPerMinute / NANOS_PER_MINUTE);
            bucket.lastRefill = now;
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return 0;
            }
            return (long) Math.ceil((1 - bucket.tokens) * 60 / limitPerMinute);
        }
    }
}
