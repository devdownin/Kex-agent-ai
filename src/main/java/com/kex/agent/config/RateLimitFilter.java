// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ne protège que les routes qui appellent le modèle : ailleurs il n'y a pas de budget à brûler,
 * et limiter l'introspection MCP gênerait la supervision sans rien préserver.
 *
 * <p>Un seau par principal authentifié, pas un seau unique pour l'instance : avec
 * {@code kex.agent.api-keys}, plusieurs opérateurs partagent l'instance sans partager leur
 * budget — une clé qui tourne en boucle ne doit pas affamer les autres. Avec le seul bearer
 * historique, il n'existe qu'un principal, donc qu'un seau : le comportement d'une installation à
 * une seule clé ne change pas.
 */
class RateLimitFilter extends OncePerRequestFilter {

    private static final String CHAT_PATH = "/api/agent/chat";

    private final RateLimitProperties properties;
    private final Map<String, TokenBucket> bucketsByPrincipal = new ConcurrentHashMap<>();

    RateLimitFilter(RateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(CHAT_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        TokenBucket bucket = bucketsByPrincipal.computeIfAbsent(principalName(),
                name -> new TokenBucket(properties.burst(), properties.requestsPerMinute()));
        if (bucket.tryConsume()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, "1");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"detail\":\"Débit de chat dépassé\"}");
    }

    /**
     * Ce filtre s'exécute après l'autorisation (voir {@code SecurityConfig}) : une route protégée
     * n'atteint jamais ce point sans authentification déjà posée. Le repli ne sert qu'à ne jamais
     * lever ici plutôt qu'à couvrir un cas réellement attendu.
     */
    private static String principalName() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null ? authentication.getName() : "inconnu";
    }
}
