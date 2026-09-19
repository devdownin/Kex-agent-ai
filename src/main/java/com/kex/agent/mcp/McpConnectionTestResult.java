// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.util.List;

public record McpConnectionTestResult(
        boolean success,
        String serverName,
        String version,
        String protocolVersion,
        long latencyMillis,
        List<String> tools,
        String message) {
}
