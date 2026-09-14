// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

public class UnknownMcpServerException extends RuntimeException {

    public UnknownMcpServerException(String connection) {
        super("Aucune connexion MCP nommée '%s'".formatted(connection));
    }
}
