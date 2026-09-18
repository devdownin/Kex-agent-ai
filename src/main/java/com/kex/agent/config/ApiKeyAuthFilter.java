// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authentification par un ou plusieurs bearers statiques. Pas de JWT ni d'OAuth : l'agent est un
 * service interne, et des jetons partagés comparés en temps constant couvrent le besoin sans
 * introduire un fournisseur d'identité dans la stack.
 *
 * <p>Le nom associé au jeton présenté devient le principal authentifié — donc l'acteur inscrit à
 * l'audit de supervision. Plusieurs opérateurs nommés distinguent qui a agi ; un seul jeton
 * anonyme (`kex-agent-api`) ne distingue jamais personne.
 *
 * <p>Le filtre authentifie, il ne refuse pas : c'est {@link ApiKeyAuthenticationEntryPoint} qui
 * répond, donc une route en {@code permitAll} (les sondes de santé) n'est jamais bloquée ici.
 */
class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    record Credential(byte[] token, Set<ApiRole> roles) {
        Credential {
            token = token.clone();
            roles = Set.copyOf(roles);
        }
    }

    private final Map<String, Credential> credentialsByName;

    ApiKeyAuthFilter(Map<String, Credential> credentialsByName) {
        this.credentialsByName = Map.copyOf(credentialsByName);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(PREFIX)) {
            authenticate(header.substring(PREFIX.length()).getBytes(StandardCharsets.UTF_8));
        }
        chain.doFilter(request, response);
    }

    private void authenticate(byte[] presented) {
        for (Map.Entry<String, Credential> candidate : credentialsByName.entrySet()) {
            if (MessageDigest.isEqual(candidate.getValue().token(), presented)) {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        candidate.getKey(), null, AuthorityUtils.createAuthorityList(candidate.getValue().roles().stream()
                                .map(role -> "ROLE_" + role.name())
                                .toArray(String[]::new))));
                return;
            }
        }
    }
}
