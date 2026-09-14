// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

public class McpServerUnavailableException extends RuntimeException {

    public McpServerUnavailableException(String connection, Throwable cause) {
        super("Serveur MCP '%s' injoignable : %s".formatted(connection, cause.getMessage()), cause);
    }
}
