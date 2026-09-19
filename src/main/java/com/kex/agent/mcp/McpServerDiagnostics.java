// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.util.List;
import java.util.Map;

public record McpServerDiagnostics(
        String connection,
        String transport,
        boolean enabled,
        boolean connected,
        int toolCount,
        List<String> conflicts,
        Map<String, String> capabilityMappings,
        List<McpHealthSample> healthHistory) {
}
