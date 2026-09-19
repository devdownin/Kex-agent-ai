// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.time.Instant;
import java.util.List;

/**
 * Diff du catalogue MCP entre deux rafraîchissements aboutis.
 *
 * @param schemaChanged outils dont le schéma d'entrée a changé ; un opérateur doit les
 *                      revalider avant de supposer que les anciens arguments restent compatibles
 */
public record McpToolDiff(Instant comparedAt, List<String> added, List<String> removed,
                          List<String> schemaChanged) {

    public static McpToolDiff empty(Instant comparedAt) {
        return new McpToolDiff(comparedAt, List.of(), List.of(), List.of());
    }

    public boolean hasChanges() {
        return !added.isEmpty() || !removed.isEmpty() || !schemaChanged.isEmpty();
    }
}
