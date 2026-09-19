// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

public class McpToolForbiddenException extends RuntimeException {

    public McpToolForbiddenException(String connection, String tool) {
        super("L'outil MCP '" + tool + "' n'est pas autorisé sur la connexion '" + connection + "'");
    }
}
