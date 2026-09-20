// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

import java.util.List;

/**
 * Un point d'accès distant déclaré par un candidat (streamable-http ou sse). {@code headerNames} ne
 * porte que des noms d'en-tête, jamais une valeur : un candidat non installé n'a droit à aucun
 * secret, et le nom seul suffit à {@link McpTrustScoreCalculator} pour juger qu'un mécanisme
 * d'authentification est au moins déclaré.
 */
public record McpCatalogRemote(String type, String url, List<String> headerNames) {
    public McpCatalogRemote {
        headerNames = headerNames == null ? List.of() : List.copyOf(headerNames);
    }
}
