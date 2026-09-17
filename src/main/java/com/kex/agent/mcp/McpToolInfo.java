// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.util.Map;

/** @param inputSchema schéma JSON des arguments, tel que déclaré par le serveur — jamais réinterprété ici */
public record McpToolInfo(String name, String description, Map<String, Object> inputSchema) {
}
