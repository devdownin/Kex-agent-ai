// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

/**
 * Agrégat de {@code kex.mcp.tool.call} par connexion et outil, toutes issues confondues (succès et
 * échec). Sert la console, où le détail par issue n'apporte rien de plus qu'un chiffre à lire vite.
 *
 * @param averageDurationMs {@code null} tant qu'aucun appel n'a eu lieu — jamais {@code 0}, une
 *                          moyenne sur zéro appel n'étant pas une durée nulle
 */
public record McpToolMetric(String connection, String tool, long callCount, Double averageDurationMs) {
}
