package com.kex.agent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Introspection des serveurs MCP connectés (diagnostic / exposition REST). */
public class McpToolCatalog {

    private static final Logger log = LoggerFactory.getLogger(McpToolCatalog.class);

    private final List<McpSyncClient> clients;

    public McpToolCatalog(List<McpSyncClient> clients) {
        this.clients = clients;
    }

    // Appel réseau/stdio synchrone par serveur : ne pas exposer sans cache sur un endpoint chaud.
    public List<McpServerInfo> servers() {
        return clients.stream().map(McpToolCatalog::describe).toList();
    }

    private static McpServerInfo describe(McpSyncClient client) {
        McpSchema.Implementation info = client.getServerInfo();
        String name = info != null ? info.name() : "unknown";
        String version = info != null ? info.version() : null;
        String protocol = client.getCurrentInitializationResult() != null
                ? client.getCurrentInitializationResult().protocolVersion()
                : null;
        return new McpServerInfo(name, version, protocol, client.isInitialized(), listTools(client, name));
    }

    private static List<McpToolInfo> listTools(McpSyncClient client, String serverName) {
        if (!client.isInitialized()) {
            return List.of();
        }
        try {
            return client.listTools().tools().stream()
                    .map(tool -> new McpToolInfo(tool.name(), tool.description()))
                    .toList();
        }
        catch (RuntimeException ex) {
            log.warn("Listing des outils MCP impossible pour le serveur {} : {}", serverName, ex.getMessage());
            return List.of();
        }
    }
}
