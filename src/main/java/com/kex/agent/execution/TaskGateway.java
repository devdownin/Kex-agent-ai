// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.execution;

import java.util.Map;

import com.kex.agent.mcp.McpToolResult;

@FunctionalInterface
public interface TaskGateway {
    default Map<String, Object> schema(String connection, String tool) { return Map.of("type", "object"); }
    McpToolResult call(String connection, String tool, Map<String, Object> arguments);
}
