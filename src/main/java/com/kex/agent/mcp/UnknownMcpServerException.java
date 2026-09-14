package com.kex.agent.mcp;

public class UnknownMcpServerException extends RuntimeException {

    public UnknownMcpServerException(String server) {
        super("Aucun serveur MCP initialisé nommé '%s'".formatted(server));
    }
}
