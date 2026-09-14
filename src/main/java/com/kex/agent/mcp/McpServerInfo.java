package com.kex.agent.mcp;

import java.util.List;

/**
 * @param connection clé de configuration ({@code spring.ai.mcp.client.*.connections.<clé>}),
 *                   connue même tant que le serveur n'a pas répondu — c'est elle qui adresse
 *                   les endpoints
 * @param serverName nom annoncé par le serveur lors de l'initialisation, {@code null} avant
 */
public record McpServerInfo(String connection, String serverName, String version, String protocolVersion,
                            boolean initialized, List<McpToolInfo> tools) {
}
