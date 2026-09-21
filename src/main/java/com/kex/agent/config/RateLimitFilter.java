// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Protège les routes qui appellent le modèle et celle qui invoque un outil MCP directement :
 * l'une consomme des jetons, l'autre déclenche un vrai traitement côté serveur MCP à chaque appel.
 * Le reste de l'introspection MCP (les routes en lecture, GET) reste libre : la limiter gênerait
 * la supervision sans rien préserver.
 *
 * <p>Un seau par principal authentifié, pas un seau unique pour l'instance : avec
 * {@code kex.agent.api-keys} ou un émetteur OIDC, plusieurs appelants partagent l'instance sans
 * partager leur budget — une clé qui tourne en boucle ne doit pas affamer les autres. Où le seau
 * est tenu — dans le processus ou dans une ligne que les répliques partagent — est la seule chose
 * que {@link RateLimiter} décide.
 */
class RateLimitFilter extends OncePerRequestFilter {

    private static final String CHAT_PATH = "/api/agent/chat";
    private static final String DIRECT_TOOL_CALL_PATTERN = "/api/agent/mcp/servers/*/tools/*";
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final RateLimiter limiter;

    RateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        boolean limited = uri.startsWith(CHAT_PATH)
                || ("POST".equals(request.getMethod()) && PATH_MATCHER.match(DIRECT_TOOL_CALL_PATTERN, uri));
        return !limited;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (limiter.tryConsume(principalName())) {
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
