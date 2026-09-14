package com.kex.agent.mcp;

public class McpServerUnavailableException extends RuntimeException {

    public McpServerUnavailableException(String connection, Throwable cause) {
        super("Serveur MCP '%s' injoignable : %s".formatted(connection, cause.getMessage()), cause);
    }
}
