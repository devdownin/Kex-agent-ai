// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.server;

import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Explicit origins only: the authenticated MCP transport never trusts an arbitrary Host header. */
@ConfigurationProperties("kex.mcp.server")
public record KexMcpServerProperties(@DefaultValue("false") boolean enabled,
                                     @DefaultValue Set<String> allowedOrigins,
                                     @DefaultValue("120") int requestsPerMinute,
                                     @DefaultValue("false") boolean governedMutationsEnabled,
                                     @DefaultValue("30m") Duration sessionTtl,
                                     @DefaultValue("5m") Duration sessionIdleAfter) {
    /** Source-compatible constructor for tests and integrations using the pre-session-lifecycle shape. */
    public KexMcpServerProperties(boolean enabled, Set<String> allowedOrigins, int requestsPerMinute,
                                  boolean governedMutationsEnabled) {
        this(enabled, allowedOrigins, requestsPerMinute, governedMutationsEnabled,
                Duration.ofMinutes(30), Duration.ofMinutes(5));
    }

    public KexMcpServerProperties {
        allowedOrigins = allowedOrigins == null ? Set.of() : Set.copyOf(allowedOrigins);
        if (requestsPerMinute < 1) throw new IllegalArgumentException("MCP server rate limit must be positive");
        if (sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()) throw new IllegalArgumentException("MCP session TTL must be positive");
        if (sessionIdleAfter == null || sessionIdleAfter.isZero() || sessionIdleAfter.isNegative() || sessionIdleAfter.compareTo(sessionTtl) >= 0) {
            throw new IllegalArgumentException("MCP session idle threshold must be positive and shorter than TTL");
        }
        if (allowedOrigins.contains("*") || allowedOrigins.contains("null")) {
            throw new IllegalArgumentException("MCP server origins must be explicit origins");
        }
    }
}
