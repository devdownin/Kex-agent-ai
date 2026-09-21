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
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    /**
     * {@code tenant} est l'espace de données de la clé, distinct de son nom — voir
     * {@link ActorIdentity}. Sans déclaration, il vaut le nom : l'installation existante retrouve
     * ses souvenirs, ses compétences et sa charte là où elle les avait laissés.
     */
    record Credential(byte[] token, Set<ApiRole> roles, String tenant) {
        Credential {
            token = token.clone();
            roles = Set.copyOf(roles);
        }
    }

    private final Map<String, Credential> credentialsByName;
    private final SecurityContextRepository securityContextRepository = new RequestAttributeSecurityContextRepository();

    ApiKeyAuthFilter(Map<String, Credential> credentialsByName) {
        this.credentialsByName = Map.copyOf(credentialsByName);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(PREFIX)) {
            authenticate(request, response, header.substring(PREFIX.length()).getBytes(StandardCharsets.UTF_8));
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, HttpServletResponse response, byte[] presented) {
        for (Map.Entry<String, Credential> candidate : credentialsByName.entrySet()) {
            if (MessageDigest.isEqual(candidate.getValue().token(), presented)) {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        new ActorIdentity(candidate.getKey(), candidate.getValue().tenant()), null,
                        AuthorityUtils.createAuthorityList(candidate.getValue().roles().stream()
                                .map(role -> "ROLE_" + role.name())
                                .toArray(String[]::new))));
                SecurityContextHolder.setContext(context);
                securityContextRepository.saveContext(context, request, response);
                return;
            }
        }
    }
}
