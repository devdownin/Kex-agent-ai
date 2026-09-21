// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

/**
 * Les deux formes d'authentification se présentent dans le même en-tête. Sans ce filtre,
 * {@code BearerTokenAuthenticationFilter} tenterait de lire une clé API comme un JWT — il
 * n'examine pas si quelqu'un s'est déjà authentifié avant lui — et une installation existante
 * basculerait en 401 le jour où on lui déclare un émetteur OIDC.
 *
 * <p>Une clé API reconnue est donc rendue invisible au serveur de ressources : c'est
 * {@code ApiKeyAuthFilter}, qui s'exécute avant, qui l'a déjà traitée. Tout le reste passe.
 * Comparaison en temps constant, comme dans le filtre : un jeton se distingue aussi par le temps
 * qu'on met à le refuser.
 */
final class ApiKeyAwareBearerTokenResolver implements BearerTokenResolver {

    private final BearerTokenResolver delegate = new DefaultBearerTokenResolver();
    private final List<byte[]> apiKeys;

    ApiKeyAwareBearerTokenResolver(List<byte[]> apiKeys) {
        this.apiKeys = apiKeys.stream().map(byte[]::clone).toList();
    }

    @Override
    public String resolve(HttpServletRequest request) {
        String token = delegate.resolve(request);
        if (token == null) {
            return null;
        }
        byte[] presented = token.getBytes(StandardCharsets.UTF_8);
        boolean known = false;
        for (byte[] apiKey : apiKeys) {
            known |= MessageDigest.isEqual(apiKey, presented);
        }
        return known ? null : token;
    }
}
