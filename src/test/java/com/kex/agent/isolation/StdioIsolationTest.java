// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.isolation;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StdioIsolationTest {

    @Test
    void validates_and_constructs_isolated_stdio_command() {
        StdioIsolationProperties policy = new StdioIsolationProperties(true, "/usr/bin/docker",
                Map.of("npx", "mcp/npx:latest"), 256, 1.0, 64);

        try (IsolatedStdioCommand cmd = new IsolatedStdioCommand(policy, "npx", List.of("-y", "server"), Map.of("KEY", "VAL"))) {
            assertThat(cmd.executable()).isEqualTo("/usr/bin/docker");
            assertThat(cmd.arguments()).contains("run", "--rm", "-i", "--entrypoint=npx", "mcp/npx:latest", "-y", "server");
        }
    }

    @Test
    void rejects_unapproved_or_invalid_commands() {
        StdioIsolationProperties policy = new StdioIsolationProperties(true, "/usr/bin/docker",
                Map.of("npx", "mcp/npx:latest"), 256, 1.0, 64);

        assertThatThrownBy(() -> new IsolatedStdioCommand(policy, "uvx", List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new IsolatedStdioCommand(policy, "-invalid", List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new IsolatedStdioCommand(policy, "npx", List.of("arg\0null"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
