// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Configuration d'une connexion MCP administrée depuis la console. */
public record McpServerRegistration(
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}") String connection,
        @Pattern(regexp = "HTTP|STDIO") String transport,
        @Size(max = 2048) String url,
        @Size(max = 256) String endpoint,
        @Size(max = 4096) String bearerToken,
        Map<@Size(max = 128) String, @Size(max = 4096) String> headers,
        @Size(max = 1024) String command,
        List<@Size(max = 2048) String> args,
        Map<@Size(max = 128) String, @Size(max = 4096) String> environment,
        Boolean enabled,
        Set<@Size(max = 256) String> allowedTools,
        Map<@Size(max = 256) String, @Size(max = 128) String> capabilityMappings) {

    public McpServerRegistration {
        transport = transport == null ? "HTTP" : transport.toUpperCase();
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        args = args == null ? List.of() : List.copyOf(args);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        enabled = enabled == null || enabled;
        allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
        capabilityMappings = capabilityMappings == null ? Map.of() : Map.copyOf(capabilityMappings);
    }

    /** Compatibilité avec le premier contrat HTTP publié avant l'ajout des transports multiples. */
    public McpServerRegistration(String connection, String url, String endpoint, String bearerToken) {
        this(connection, "HTTP", url, endpoint, bearerToken, Map.of(), null, List.of(), Map.of(), true,
                Set.of(), Map.of());
    }

    public McpServerRegistration withoutSecrets() {
        return new McpServerRegistration(connection, transport, url, endpoint, null, headers.keySet().stream()
                .collect(java.util.stream.Collectors.toMap(key -> key, key -> "")), command, args,
                environment.keySet().stream().collect(java.util.stream.Collectors.toMap(key -> key, key -> "")),
                false, allowedTools, capabilityMappings);
    }
}
