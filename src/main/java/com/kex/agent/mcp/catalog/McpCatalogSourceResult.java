// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

import java.util.List;

/**
 * Un registre externe injoignable est une information d'exploitation, pas une panne de l'agent :
 * {@code error} le nomme et {@code candidates} reste vide, jamais l'inverse (une liste vide sans
 * motif se lirait « ce registre n'a rien à offrir » là où la phrase vraie est « ce registre n'a pas
 * répondu ») — même doctrine que la vue technique Kafka.
 */
public record McpCatalogSourceResult(String sourceId, String label, List<McpCatalogCandidate> candidates,
                                     String error) {
    public McpCatalogSourceResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public static McpCatalogSourceResult ok(String sourceId, String label, List<McpCatalogCandidate> candidates) {
        return new McpCatalogSourceResult(sourceId, label, candidates, null);
    }

    public static McpCatalogSourceResult failed(String sourceId, String label, String error) {
        return new McpCatalogSourceResult(sourceId, label, List.of(), error);
    }
}
