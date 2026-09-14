package com.kex.agent.mcp;

public class UnsupportedMcpCapabilityException extends RuntimeException {

    public UnsupportedMcpCapabilityException(String connection, String capability) {
        super("Le serveur MCP '%s' n'expose pas la capacité '%s'".formatted(connection, capability));
    }
}
