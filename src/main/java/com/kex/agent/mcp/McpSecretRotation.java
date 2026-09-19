// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.util.Map;

import jakarta.validation.constraints.Size;

public record McpSecretRotation(
        @Size(max = 4096) String bearerToken,
        Map<@Size(max = 128) String, @Size(max = 4096) String> headers,
        Map<@Size(max = 128) String, @Size(max = 4096) String> environment) {

    public McpSecretRotation {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }
}
