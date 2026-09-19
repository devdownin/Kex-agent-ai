// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record McpConfigurationBundle(int version, @NotNull List<@Valid McpServerRegistration> servers) {

    public McpConfigurationBundle {
        servers = servers == null ? List.of() : List.copyOf(servers);
    }
}
