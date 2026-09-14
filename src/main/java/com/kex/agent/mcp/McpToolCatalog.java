package com.kex.agent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/** Introspection et invocation directe des serveurs MCP connectés. */
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

    /**
     * Invocation directe, sans passage par le modèle : le contrôle d'accès n'est porté que par
     * l'appelant, contrairement au flux ChatClient où le LLM choisit l'outil.
     */
    public McpToolResult call(String server, String tool, Map<String, Object> arguments) {
        McpSyncClient client = clients.stream()
                .filter(McpSyncClient::isInitialized)
                .filter(candidate -> server.equals(serverName(candidate)))
                .findFirst()
                .orElseThrow(() -> new UnknownMcpServerException(server));

        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest(tool, arguments == null ? Map.of() : arguments));

        return new McpToolResult(server, tool, Boolean.TRUE.equals(result.isError()),
                textOf(result.content()), result.structuredContent());
    }

    private static List<String> textOf(List<McpSchema.Content> content) {
        if (content == null) {
            return List.of();
        }
        return content.stream()
                .map(part -> part instanceof McpSchema.TextContent text ? text.text() : "[" + part.type() + "]")
                .toList();
    }

    private static McpServerInfo describe(McpSyncClient client) {
        String name = serverName(client);
        McpSchema.Implementation info = client.getServerInfo();
        String protocol = client.getCurrentInitializationResult() != null
                ? client.getCurrentInitializationResult().protocolVersion()
                : null;
        return new McpServerInfo(name, info != null ? info.version() : null, protocol, client.isInitialized(),
                listTools(client, name));
    }

    private static String serverName(McpSyncClient client) {
        McpSchema.Implementation info = client.getServerInfo();
        return info != null ? info.name() : "unknown";
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
