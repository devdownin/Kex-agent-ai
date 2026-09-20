// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Éteintes par défaut, comme chaque extension qui ajoute une sortie réseau propre à l'agent
 * (automation, channels, mcp.server, routage multi-modèles) : interroger un registre tiers à
 * chaque chargement du catalogue est un choix d'opérateur, pas un comportement d'installation par
 * défaut. {@code baseUrl} est fourni par {@code application.yml} (avec sa vraie valeur par défaut,
 * substituable par variable d'environnement comme {@code KAFKA_EXPLORER_URL} ailleurs) plutôt que
 * par une annotation Java : les deux sources partagent le même type {@link Source}, qui ne peut
 * donc pas porter deux URL par défaut distinctes.
 */
@ConfigurationProperties("kex.mcp.catalog.sources")
public record McpCatalogSourcesProperties(@DefaultValue Source docker, @DefaultValue Source officialRegistry) {

    public record Source(@DefaultValue("false") boolean enabled, @DefaultValue("") String baseUrl,
                         @DefaultValue("2") int maxPages, @DefaultValue("100") int pageSize,
                         @DefaultValue("5s") Duration requestTimeout) {
        public Source {
            if (maxPages < 1 || maxPages > 20) {
                throw new IllegalArgumentException("kex.mcp.catalog.sources.*.max-pages doit être compris entre 1 et 20");
            }
            if (pageSize < 1 || pageSize > 100) {
                throw new IllegalArgumentException("kex.mcp.catalog.sources.*.page-size doit être compris entre 1 et 100");
            }
            if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
                throw new IllegalArgumentException("kex.mcp.catalog.sources.*.request-timeout doit être positif");
            }
        }
    }
}
