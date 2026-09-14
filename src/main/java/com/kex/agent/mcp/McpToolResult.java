// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.util.List;

/**
 * @param error  drapeau {@code isError} renvoyé par le serveur MCP : l'appel a abouti,
 *               c'est l'outil qui a échoué (argument invalide, fichier absent...).
 */
public record McpToolResult(String connection, String tool, boolean error, List<String> content,
                            Object structuredContent) {
}
