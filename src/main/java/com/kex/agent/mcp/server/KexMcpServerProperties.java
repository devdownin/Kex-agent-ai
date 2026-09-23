// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.server;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Explicit origins only: the authenticated MCP transport never trusts an arbitrary Host header. */
@ConfigurationProperties("kex.mcp.server")
public record KexMcpServerProperties(@DefaultValue("false") boolean enabled,
                                     @DefaultValue Set<String> allowedOrigins,
                                     @DefaultValue("120") int requestsPerMinute) {
    public KexMcpServerProperties {
        allowedOrigins = allowedOrigins == null ? Set.of() : Set.copyOf(allowedOrigins);
        if (requestsPerMinute < 1) throw new IllegalArgumentException("MCP server rate limit must be positive");
        if (allowedOrigins.contains("*") || allowedOrigins.contains("null")) {
            throw new IllegalArgumentException("MCP server origins must be explicit origins");
        }
    }
}
