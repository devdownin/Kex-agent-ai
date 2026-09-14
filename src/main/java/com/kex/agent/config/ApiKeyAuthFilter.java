// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authentification par bearer statique. Pas de JWT ni d'OAuth : l'agent est un service interne,
 * et un jeton partagé comparé en temps constant couvre le besoin sans introduire un fournisseur
 * d'identité dans la stack.
 *
 * <p>Le filtre authentifie, il ne refuse pas : c'est {@link ApiKeyAuthenticationEntryPoint} qui
 * répond, donc une route en {@code permitAll} (les sondes de santé) n'est jamais bloquée ici.
 */
class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final byte[] expected;

    ApiKeyAuthFilter(String apiKey) {
        this.expected = StringUtils.hasText(apiKey) ? apiKey.getBytes(StandardCharsets.UTF_8) : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (expected != null && header != null && header.startsWith(PREFIX)) {
            byte[] presented = header.substring(PREFIX.length()).getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(expected, presented)) {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        "kex-agent-api", null, AuthorityUtils.NO_AUTHORITIES));
            }
        }
        chain.doFilter(request, response);
    }
}
