// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Jetons porteurs à injecter sur les transports MCP HTTP, par préfixe d'URL. */
@ConfigurationProperties("kex.mcp")
public record McpAuthProperties(@DefaultValue List<BearerToken> bearerTokens) {

    public record BearerToken(String urlPrefix, String token) {
    }
}
