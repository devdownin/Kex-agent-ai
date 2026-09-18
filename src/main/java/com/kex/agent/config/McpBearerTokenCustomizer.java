// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * Le transport streamable-http de Spring AI ne porte pas d'en-têtes dans ses propriétés :
 * l'authentification passe par ce customizer, appliqué à toutes les connexions HTTP MCP,
 * d'où le filtrage sur l'origine et la frontière du chemin pour ne pas fuiter un jeton vers
 * un autre serveur.
 */
class McpBearerTokenCustomizer implements McpSyncHttpClientRequestCustomizer {

    private static final Logger log = LoggerFactory.getLogger(McpBearerTokenCustomizer.class);

    private record Credential(URI base, String token) {
    }

    private final List<Credential> credentials;

    McpBearerTokenCustomizer(List<McpAuthProperties.BearerToken> tokens) {
        this.credentials = tokens.stream()
                .map(McpBearerTokenCustomizer::credential)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(
                        (Credential credential) -> normalizedPath(credential.base()).length()).reversed())
                .toList();
    }

    /**
     * Un préfixe sans jeton est la forme même d'un serveur MCP par défaut qui ne demande aucune
     * authentification — {@code application.yml} en fournit un pour Kafka Explorer — donc pas
     * d'avertissement pour ça. Un jeton sans préfixe, à l'inverse, ne s'appliquera jamais à aucune
     * requête ({@link #customize} ne le pose que si l'origine et le chemin correspondent) : c'est
     * l'entrée mal renseignée qui se découvrait jusqu'ici par un 401 du serveur distant, sans qu'un
     * journal ne rapproche l'un de l'autre.
     */
    private static Credential credential(McpAuthProperties.BearerToken token) {
        boolean prefix = StringUtils.hasText(token.urlPrefix());
        boolean value = StringUtils.hasText(token.token());
        if (value && !prefix) {
            log.warn("Entrée kex.mcp.bearer-tokens ignorée : un jeton est déclaré sans url-prefix, "
                    + "il ne s'appliquera à aucune requête");
        }
        if (!prefix || !value) {
            return null;
        }
        try {
            URI base = URI.create(token.urlPrefix()).normalize();
            if (!base.isAbsolute() || base.getHost() == null || base.getUserInfo() != null
                    || base.getQuery() != null || base.getFragment() != null) {
                throw new IllegalArgumentException("URL absolue sans userinfo, query ni fragment requise");
            }
            return new Credential(base, token.token());
        }
        catch (IllegalArgumentException ex) {
            log.warn("Entrée kex.mcp.bearer-tokens ignorée : url-prefix invalide ({})", ex.getMessage());
            return null;
        }
    }

    @Override
    public void customize(HttpRequest.Builder builder, String method, URI uri, String body,
                          McpTransportContext context) {
        credentials.stream()
                .filter(credential -> matches(credential.base(), uri))
                .findFirst()
                .ifPresent(credential -> builder.header("Authorization", "Bearer " + credential.token()));
    }

    private static boolean matches(URI base, URI request) {
        if (!base.getScheme().equalsIgnoreCase(request.getScheme())
                || !base.getHost().equalsIgnoreCase(request.getHost())
                || port(base) != port(request)) {
            return false;
        }
        String basePath = normalizedPath(base);
        String requestPath = normalizedPath(request.normalize());
        return "/".equals(basePath) || requestPath.equals(basePath)
                || requestPath.startsWith(basePath.endsWith("/") ? basePath : basePath + "/");
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443
                : "http".equalsIgnoreCase(uri.getScheme()) ? 80 : -1;
    }

    private static String normalizedPath(URI uri) {
        return uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
    }
}
