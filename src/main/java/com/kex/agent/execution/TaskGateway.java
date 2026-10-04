// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.execution;

import java.util.Map;

import com.kex.agent.mcp.McpToolResult;

@FunctionalInterface
public interface TaskGateway {
    default Map<String, Object> schema(String connection, String tool) { return Map.of("type", "object"); }
    default McpToolResult call(String connection, String tool, Map<String, Object> arguments,
            com.kex.agent.agent.TokenBudgetService budget, String owner, String task) {
        if (budget != null) budget.reserveTool(owner, task, connection + ":" + tool);
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Appel interrompu avant exécution");
        return call(connection, tool, arguments);
    }
    McpToolResult call(String connection, String tool, Map<String, Object> arguments);
}
