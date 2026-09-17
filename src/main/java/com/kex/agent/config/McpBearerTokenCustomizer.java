// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * Le transport streamable-http de Spring AI ne porte pas d'en-têtes dans ses propriétés :
 * l'authentification passe par ce customizer, appliqué à toutes les connexions HTTP MCP,
 * d'où le filtrage sur le préfixe d'URL pour ne pas fuiter un jeton vers un autre serveur.
 */
class McpBearerTokenCustomizer implements McpSyncHttpClientRequestCustomizer {

    private static final Logger log = LoggerFactory.getLogger(McpBearerTokenCustomizer.class);

    private final List<McpAuthProperties.BearerToken> tokens;

    McpBearerTokenCustomizer(List<McpAuthProperties.BearerToken> tokens) {
        this.tokens = tokens.stream()
                .filter(McpBearerTokenCustomizer::keep)
                .toList();
    }

    /**
     * Un préfixe sans jeton est la forme même d'un serveur MCP par défaut qui ne demande aucune
     * authentification — {@code application.yml} en fournit un pour Kafka Explorer — donc pas
     * d'avertissement pour ça. Un jeton sans préfixe, à l'inverse, ne s'appliquera jamais à aucune
     * requête ({@link #customize} ne le pose que si l'URL commence par le préfixe déclaré) : c'est
     * l'entrée mal renseignée qui se découvrait jusqu'ici par un 401 du serveur distant, sans qu'un
     * journal ne rapproche l'un de l'autre.
     */
    private static boolean keep(McpAuthProperties.BearerToken token) {
        boolean prefix = StringUtils.hasText(token.urlPrefix());
        boolean value = StringUtils.hasText(token.token());
        if (value && !prefix) {
            log.warn("Entrée kex.mcp.bearer-tokens ignorée : un jeton est déclaré sans url-prefix, "
                    + "il ne s'appliquera à aucune requête");
        }
        return prefix && value;
    }

    @Override
    public void customize(HttpRequest.Builder builder, String method, URI uri, String body,
                          McpTransportContext context) {
        String url = uri.toString();
        tokens.stream()
                .filter(token -> url.startsWith(token.urlPrefix()))
                .findFirst()
                .ifPresent(token -> builder.header("Authorization", "Bearer " + token.token()));
    }
}
