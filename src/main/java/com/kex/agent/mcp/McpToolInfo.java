// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.util.Map;

/** @param inputSchema schéma JSON des arguments, tel que déclaré par le serveur — jamais réinterprété ici */
public record McpToolInfo(String name, String description, Map<String, Object> inputSchema,
                          Map<String, Boolean> annotations, boolean readOnlyByPolicy, Map<String, Object> outputSchema) {
    public McpToolInfo(String name, String description, Map<String, Object> inputSchema) {
        this(name, description, inputSchema, Map.of(), false, null);
    }
    public McpToolInfo(String name, String description, Map<String, Object> inputSchema,
                       Map<String, Boolean> annotations, boolean readOnlyByPolicy) {
        this(name, description, inputSchema, annotations, readOnlyByPolicy, null);
    }
}
