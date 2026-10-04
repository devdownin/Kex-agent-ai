// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.util.List;

public record McpPromptInfo(String name, String description, List<Argument> arguments) {
    public record Argument(String name, String description, boolean required) {
    }
}
