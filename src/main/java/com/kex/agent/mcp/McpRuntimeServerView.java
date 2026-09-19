// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Configuration administrable, toujours expurgée de ses secrets. */
public record McpRuntimeServerView(
        String connection,
        String transport,
        String url,
        String endpoint,
        String command,
        List<String> args,
        Set<String> headerNames,
        Set<String> environmentNames,
        boolean enabled,
        Set<String> allowedTools,
        Map<String, String> capabilityMappings,
        boolean hasBearerToken,
        Instant secretRotatedAt) {
}
