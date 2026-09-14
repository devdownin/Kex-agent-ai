package com.kex.agent.config;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;

/**
 * Le transport streamable-http de Spring AI ne porte pas d'en-têtes dans ses propriétés :
 * l'authentification passe par ce customizer, appliqué à toutes les connexions HTTP MCP,
 * d'où le filtrage sur le préfixe d'URL pour ne pas fuiter un jeton vers un autre serveur.
 */
class McpBearerTokenCustomizer implements McpSyncHttpClientRequestCustomizer {

    private final List<McpAuthProperties.BearerToken> tokens;

    McpBearerTokenCustomizer(List<McpAuthProperties.BearerToken> tokens) {
        this.tokens = tokens.stream()
                .filter(token -> StringUtils.hasText(token.urlPrefix()) && StringUtils.hasText(token.token()))
                .toList();
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
