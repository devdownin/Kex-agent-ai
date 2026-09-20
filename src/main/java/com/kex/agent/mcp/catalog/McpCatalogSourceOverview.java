// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

import java.util.List;

/** Une source désactivée ou injoignable le dit ({@code enabled}/{@code error}), jamais une liste vide muette. */
public record McpCatalogSourceOverview(String sourceId, String label, boolean enabled, String error,
                                       List<McpCatalogDiscoveredCandidate> candidates) {
    public McpCatalogSourceOverview {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    static McpCatalogSourceOverview disabled(String sourceId, String label) {
        return new McpCatalogSourceOverview(sourceId, label, false, null, List.of());
    }

    static McpCatalogSourceOverview failed(String sourceId, String label, String error) {
        return new McpCatalogSourceOverview(sourceId, label, true, error, List.of());
    }

    static McpCatalogSourceOverview ok(String sourceId, String label, List<McpCatalogDiscoveredCandidate> candidates) {
        return new McpCatalogSourceOverview(sourceId, label, true, null, candidates);
    }
}
