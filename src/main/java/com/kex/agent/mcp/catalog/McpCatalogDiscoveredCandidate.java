// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

/** Un candidat de découverte, sa note recalculée à chaque relevé — jamais mise en cache avec lui. */
public record McpCatalogDiscoveredCandidate(McpCatalogCandidate candidate, McpTrustScore score) {
}
