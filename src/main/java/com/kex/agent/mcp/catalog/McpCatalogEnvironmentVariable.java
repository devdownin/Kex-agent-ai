// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

/** Variable d'environnement déclarée par un paquet MCP découvert, jamais sa valeur. */
public record McpCatalogEnvironmentVariable(String name, boolean required, boolean secret, String defaultValue) {
}
