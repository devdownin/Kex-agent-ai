// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

/**
 * Un registre externe de serveurs MCP à interroger pour peupler le catalogue de découverte.
 * {@link #fetch()} ne lève jamais : un registre injoignable rend un {@link McpCatalogSourceResult}
 * qui le dit, pour que la panne d'une source n'efface pas les autres.
 */
public interface McpCatalogSource {
    String id();

    String label();

    boolean enabled();

    McpCatalogSourceResult fetch();
}
