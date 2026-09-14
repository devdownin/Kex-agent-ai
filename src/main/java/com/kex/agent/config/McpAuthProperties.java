package com.kex.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/** Jetons porteurs à injecter sur les transports MCP HTTP, par préfixe d'URL. */
@ConfigurationProperties("kex.mcp")
public record McpAuthProperties(@DefaultValue List<BearerToken> bearerTokens) {

    public record BearerToken(String urlPrefix, String token) {
    }
}
