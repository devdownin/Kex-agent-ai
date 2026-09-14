// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Introspection et invocation directe des serveurs MCP connectés. Les clients sont initialisés
 * paresseusement ({@code spring.ai.mcp.client.initialized=false}) pour que l'agent démarre même
 * si un serveur distant est indisponible : chaque accès retente l'initialisation des clients
 * encore muets.
 */
public class McpToolCatalog {

    private static final Logger log = LoggerFactory.getLogger(McpToolCatalog.class);

    private static final String CLIENT_NAME_SEPARATOR = " - ";

    private final List<McpSyncClient> clients;
    private final ObservationRegistry observationRegistry;

    public McpToolCatalog(List<McpSyncClient> clients, ObservationRegistry observationRegistry) {
        this.clients = clients;
        this.observationRegistry = observationRegistry;
    }

    // Un appel réseau/stdio par serveur : ne pas exposer sans cache sur un endpoint chaud.
    public List<McpServerInfo> servers() {
        initializePending();
        return clients.stream().map(McpToolCatalog::describe).toList();
    }

    /**
     * Invocation directe, sans passage par le modèle : le contrôle d'accès n'est porté que par
     * l'appelant, contrairement au flux ChatClient où le LLM choisit l'outil.
     */
    public McpToolResult call(String connection, String tool, Map<String, Object> arguments) {
        // Ce chemin ne passe pas par Spring AI, donc pas par ses observations : sans ce timer,
        // la latence et les échecs de l'appel direct ne seraient mesurés nulle part. Connexions et
        // noms d'outils sont bornés, la cardinalité le reste aussi.
        return Observation.createNotStarted("kex.mcp.tool.call", observationRegistry)
                .lowCardinalityKeyValue("connection", connection)
                .lowCardinalityKeyValue("tool", tool)
                .observe(() -> {
                    McpSchema.CallToolResult result = client(connection).callTool(
                            new McpSchema.CallToolRequest(tool, arguments == null ? Map.of() : arguments));
                    return new McpToolResult(connection, tool, Boolean.TRUE.equals(result.isError()),
                            textOf(result.content()), result.structuredContent());
                });
    }

    /** Ressources exposées par un serveur : vide si le serveur ne déclare pas la capacité. */
    public List<McpResourceInfo> resources(String connection) {
        McpSyncClient client = client(connection);
        if (!supportsResources(client)) {
            return List.of();
        }
        return client.listResources().resources().stream()
                .map(resource -> new McpResourceInfo(resource.uri(), resource.name(), resource.description(),
                        resource.mimeType(), resource.size()))
                .toList();
    }

    public List<McpResourceContent> readResource(String connection, String uri) {
        McpSyncClient client = client(connection);
        if (!supportsResources(client)) {
            throw new UnsupportedMcpCapabilityException(connection, "resources");
        }
        return client.readResource(new McpSchema.ReadResourceRequest(uri)).contents().stream()
                .map(McpToolCatalog::toContent)
                .toList();
    }

    private McpSyncClient client(String connection) {
        McpSyncClient client = find(connection).orElseThrow(() -> new UnknownMcpServerException(connection));
        if (!client.isInitialized()) {
            try {
                client.initialize();
            }
            catch (RuntimeException ex) {
                throw new McpServerUnavailableException(connection, ex);
            }
        }
        return client;
    }

    private Optional<McpSyncClient> find(String connection) {
        return clients.stream()
                .filter(candidate -> connection.equals(connectionName(candidate)))
                .findFirst();
    }

    /** Le SDK gère l'initialisation concurrente : pas de verrou côté appelant. */
    private void initializePending() {
        clients.stream().filter(client -> !client.isInitialized()).forEach(client -> {
            try {
                client.initialize();
            }
            catch (RuntimeException ex) {
                log.warn("Serveur MCP '{}' injoignable : {}", connectionName(client), ex.getMessage());
            }
        });
    }

    private static McpServerInfo describe(McpSyncClient client) {
        McpSchema.Implementation info = client.getServerInfo();
        McpSchema.InitializeResult initialization = client.getCurrentInitializationResult();
        return new McpServerInfo(connectionName(client),
                info != null ? info.name() : null,
                info != null ? info.version() : null,
                initialization != null ? initialization.protocolVersion() : null,
                client.isInitialized(),
                listTools(client));
    }

    /** {@code spring.ai.mcp.client.name} + " - " + clé de connexion, posé par l'autoconfiguration. */
    private static String connectionName(McpSyncClient client) {
        McpSchema.Implementation clientInfo = client.getClientInfo();
        if (clientInfo == null || clientInfo.name() == null) {
            return "unknown";
        }
        int separator = clientInfo.name().lastIndexOf(CLIENT_NAME_SEPARATOR);
        return separator < 0 ? clientInfo.name() : clientInfo.name().substring(separator + CLIENT_NAME_SEPARATOR.length());
    }

    private static List<McpToolInfo> listTools(McpSyncClient client) {
        if (!client.isInitialized()) {
            return List.of();
        }
        try {
            return client.listTools().tools().stream()
                    .map(tool -> new McpToolInfo(tool.name(), tool.description()))
                    .toList();
        }
        catch (RuntimeException ex) {
            log.warn("Listing des outils MCP impossible pour '{}' : {}", connectionName(client), ex.getMessage());
            return List.of();
        }
    }

    private static McpResourceContent toContent(McpSchema.ResourceContents contents) {
        return switch (contents) {
            case McpSchema.TextResourceContents text ->
                    new McpResourceContent(text.uri(), text.mimeType(), text.text(), null);
            case McpSchema.BlobResourceContents blob ->
                    new McpResourceContent(blob.uri(), blob.mimeType(), null, blob.blob());
            default -> new McpResourceContent(contents.uri(), contents.mimeType(), null, null);
        };
    }

    private static boolean supportsResources(McpSyncClient client) {
        return client.getServerCapabilities() != null && client.getServerCapabilities().resources() != null;
    }

    private static List<String> textOf(List<McpSchema.Content> content) {
        if (content == null) {
            return List.of();
        }
        return content.stream()
                .map(part -> part instanceof McpSchema.TextContent text ? text.text() : "[" + part.type() + "]")
                .toList();
    }
}
