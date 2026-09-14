// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.web;

import java.util.Map;

public record McpToolCallRequest(Map<String, Object> arguments) {

    public Map<String, Object> arguments() {
        return arguments == null ? Map.of() : arguments;
    }
}
