// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

/** Rend les outils ajoutés à chaud visibles au ChatClient, qui interroge le provider à chaque appel. */
@Component
public class DynamicMcpToolCallbackProvider implements ToolCallbackProvider {

    private final McpToolCatalog catalog;
    private final ObjectMapper objectMapper;

    DynamicMcpToolCallbackProvider(McpToolCatalog catalog, ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return catalog.dynamicServers().stream()
                .flatMap(server -> server.tools().stream()
                        .filter(tool -> catalog.isToolAllowed(server.connection(), tool.name()))
                        .map(tool -> callback(server.connection(), tool)))
                .toArray(ToolCallback[]::new);
    }

    private ToolCallback callback(String connection, McpToolInfo tool) {
        String name = McpToolCatalog.callbackPrefix(connection) + "__" + tool.name();
        String capability = catalog.capability(connection, tool.name());
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(name)
                .description((tool.description() == null ? "Outil MCP " + tool.name() : tool.description())
                        + (capability == null ? "" : " [capacité: " + capability + "]"))
                .inputSchema(json(tool.inputSchema()))
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String input) {
                Map<String, Object> arguments;
                try {
                    arguments = objectMapper.readValue(input, new TypeReference<>() { });
                }
                catch (Exception ex) {
                    throw new IllegalArgumentException("Arguments MCP invalides", ex);
                }
                return String.join("\n", catalog.call(connection, tool.name(), arguments).content());
            }
        };
    }

    private String json(Map<String, Object> schema) {
        try {
            return objectMapper.writeValueAsString(schema == null ? Map.of("type", "object") : schema);
        }
        catch (Exception ex) {
            return "{\"type\":\"object\"}";
        }
    }
}
