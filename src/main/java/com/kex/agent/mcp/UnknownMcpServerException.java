package com.kex.agent.mcp;

public class UnknownMcpServerException extends RuntimeException {

    public UnknownMcpServerException(String connection) {
        super("Aucune connexion MCP nommée '%s'".formatted(connection));
    }
}
