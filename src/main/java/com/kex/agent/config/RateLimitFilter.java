package com.kex.agent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Ne protège que les routes qui appellent le modèle : ailleurs il n'y a pas de budget à brûler,
 * et limiter l'introspection MCP gênerait la supervision sans rien préserver.
 */
class RateLimitFilter extends OncePerRequestFilter {

    private static final String CHAT_PATH = "/api/agent/chat";

    private final TokenBucket bucket;

    RateLimitFilter(RateLimitProperties properties) {
        this.bucket = new TokenBucket(properties.burst(), properties.requestsPerMinute());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(CHAT_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (bucket.tryConsume()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, "1");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"detail\":\"Débit de chat dépassé\"}");
    }
}
