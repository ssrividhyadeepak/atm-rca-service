package com.srividhya.bankrca.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Rate limits the REST API per client. It runs after authentication, so a request without a
 * valid token is refused before it can use up anyone's allowance.
 */
@Configuration
public class RateLimitInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private final RateLimiter limiter;
    private final RateLimitProps limits;
    private final Caller caller;

    public RateLimitInterceptor(RateLimiter limiter, RateLimitProps limits, Caller caller) {
        this.limiter = limiter;
        this.limits = limits;
        this.caller = caller;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        boolean post = "POST".equals(request.getMethod());
        String bucket;
        int limit;
        if (path.equals("/api/failures") || path.equals("/api/correlation")
                || (post && (path.equals("/api/runs") || path.equals("/api/rca")))) {
            // Each of these runs a Splunk search
            bucket = "search";
            limit = limits.searchPerMinute();
        } else if (path.startsWith("/api/assistant")) {
            bucket = "assistant";
            limit = limits.assistantPerMinute();
        } else {
            bucket = "api";
            limit = limits.defaultPerMinute();
        }
        long retryAfter = limiter.tryAcquire(caller.client(), bucket, limit);
        if (retryAfter == 0) {
            return true;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"Rate limit reached: at most " + limit + " of these calls per minute. "
                + "Retry in " + retryAfter + "s\",\"retryAfterSeconds\":" + retryAfter + "}");
        return false;
    }
}
