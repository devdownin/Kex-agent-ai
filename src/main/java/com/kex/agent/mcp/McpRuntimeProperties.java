// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Stockage local des connexions MCP créées dans la console. */
@ConfigurationProperties("kex.mcp.runtime")
public record McpRuntimeProperties(
        @DefaultValue(".kex/mcp-servers.enc") String storagePath,
        String storageKey,
        @DefaultValue("30s") Duration requestTimeout,
        @DefaultValue("50") int healthHistorySize,
        @DefaultValue List<String> allowedStdioCommands,
        @DefaultValue com.kex.agent.isolation.StdioIsolationProperties isolation) {

    public McpRuntimeProperties(String storagePath, String storageKey, Duration requestTimeout,
                                int healthHistorySize, List<String> allowedStdioCommands) {
        this(storagePath, storageKey, requestTimeout, healthHistorySize, allowedStdioCommands,
                com.kex.agent.isolation.StdioIsolationProperties.defaults());
    }

    public McpRuntimeProperties {
        storageKey = storageKey == null ? "" : storageKey;
        isolation = isolation == null ? com.kex.agent.isolation.StdioIsolationProperties.defaults() : isolation;
        allowedStdioCommands = allowedStdioCommands == null ? List.of() : List.copyOf(allowedStdioCommands);
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("kex.mcp.runtime.request-timeout doit être positif");
        }
        if (healthHistorySize < 1) {
            throw new IllegalArgumentException("kex.mcp.runtime.health-history-size doit être positif");
        }
    }
}
