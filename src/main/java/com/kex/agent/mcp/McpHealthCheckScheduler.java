// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import org.springframework.scheduling.annotation.Scheduled;

/**
 * Retente l'initialisation des clients MCP encore muets, sans attendre qu'une requête entrante le
 * fasse à sa place. Sans verrou multi-instance : contrairement au cycle de supervision, chaque
 * réplique gère ses propres clients MCP et n'entreprend aucune action partagée en les retentant.
 */
class McpHealthCheckScheduler {

    private final McpToolCatalog catalog;

    McpHealthCheckScheduler(McpToolCatalog catalog) {
        this.catalog = catalog;
    }

    @Scheduled(fixedDelayString = "${kex.mcp.health-check.interval:1m}")
    void retryUnreachable() {
        catalog.initializePending();
    }
}
