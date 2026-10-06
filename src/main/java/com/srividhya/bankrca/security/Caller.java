package com.srividhya.bankrca.security;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Who is making the current call, and whether they may make it. URL rules cover the REST
 * endpoints; this is for the checks that cannot be done by URL - a tool call, whose scope
 * depends on which tool it is.
 */
@Component
public class Caller {

    public static class DeniedException extends RuntimeException {
        public DeniedException(String message) {
            super(message);
        }
    }

    public static class RateLimitedException extends RuntimeException {
        private final long retryAfterSeconds;

        public RateLimitedException(String message, long retryAfterSeconds) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private static final String SCOPE_PREFIX = "SCOPE_";

    private final SecurityProps security;
    private final RateLimitProps limits;
    private final RateLimiter limiter;

    public Caller(SecurityProps security, RateLimitProps limits, RateLimiter limiter) {
        this.security = security;
        this.limits = limits;
        this.limiter = limiter;
    }

    /** The token's subject; "local" when security is off or the call does not come from a request (the scheduler). */
    public String client() {
        Authentication auth = authentication();
        return auth == null ? "local" : auth.getName();
    }

    /**
     * @throws DeniedException when the caller's token lacks one of the scopes
     * @throws RateLimitedException when the caller has used up its allowance for the tool
     */
    public void authorizeTool(String tool, Set<String> requiredScopes) {
        Authentication auth = authentication();
        if (!security.off()) {
            if (auth == null) {
                throw new DeniedException("Not authenticated");
            }
            Set<String> granted = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                    .filter(a -> a.startsWith(SCOPE_PREFIX)).map(a -> a.substring(SCOPE_PREFIX.length()))
                    .collect(Collectors.toSet());
            Set<String> missing = new TreeSet<>(requiredScopes);
            missing.removeAll(granted);
            if (!missing.isEmpty()) {
                throw new DeniedException("Access denied: " + tool + " needs scope " + String.join(" and ", missing));
            }
        }
        long retryAfter = limiter.tryAcquire(client(), "tool:" + tool, limits.toolPerMinute());
        if (retryAfter > 0) {
            throw new RateLimitedException("Rate limit reached for " + tool + ": at most " + limits.toolPerMinute()
                    + " calls per minute. Retry in " + retryAfter + "s", retryAfter);
        }
    }

    private static Authentication authentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken ? null : auth;
    }
}
