// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

import java.util.Set;

/** Curated remote endpoints, without an executable command or user-controlled destination. */
public record McpCatalogEntry(String id, String name, String description, String url, String endpoint,
                               String documentation, boolean requiresToken, Set<String> allowedTools) {
    public McpCatalogEntry {
        allowedTools = Set.copyOf(allowedTools);
        if (allowedTools.isEmpty()) throw new IllegalArgumentException("Catalog entries require an explicit tool allowlist");
    }
}
