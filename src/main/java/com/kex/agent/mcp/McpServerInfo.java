// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.util.List;

/**
 * @param connection clé de configuration ({@code spring.ai.mcp.client.*.connections.<clé>}),
 *                   connue même tant que le serveur n'a pas répondu — c'est elle qui adresse
 *                   les endpoints
 * @param serverName nom annoncé par le serveur lors de l'initialisation, {@code null} avant
 * @param circuitBreakerState état du disjoncteur propre à cette connexion ({@code CLOSED}/{@code
 *                            OPEN}/{@code HALF_OPEN}...), pour l'appel direct uniquement — le
 *                            chemin piloté par le modèle reste sur le disjoncteur partagé
 *                            {@code mcp-tool}, {@link io.modelcontextprotocol.client.McpSyncClient}
 *                            n'exposant pas sa connexion à ce niveau-là
 */
public record McpServerInfo(String connection, String serverName, String version, String protocolVersion,
                            boolean initialized, String circuitBreakerState, List<McpToolInfo> tools) {
}
