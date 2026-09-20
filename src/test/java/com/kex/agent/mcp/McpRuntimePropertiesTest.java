// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.time.Duration;
import java.util.List;

import com.kex.agent.isolation.StdioIsolationProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpRuntimePropertiesTest {

    @Test
    void validates_mcp_runtime_properties() {
        McpRuntimeProperties props = new McpRuntimeProperties(".kex/mcp-servers.enc", "key", Duration.ofSeconds(30), 50, List.of("npx"));

        assertThat(props.storagePath()).isEqualTo(".kex/mcp-servers.enc");
        assertThat(props.storageKey()).isEqualTo("key");
        assertThat(props.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.healthHistorySize()).isEqualTo(50);
        assertThat(props.allowedStdioCommands()).containsExactly("npx");
        assertThat(props.isolation()).isNotNull();

        // Invalid requestTimeout
        assertThatThrownBy(() -> new McpRuntimeProperties(".kex/path", "key", Duration.ZERO, 50, List.of(), StdioIsolationProperties.defaults()))
                .isInstanceOf(IllegalArgumentException.class);

        // Invalid healthHistorySize
        assertThatThrownBy(() -> new McpRuntimeProperties(".kex/path", "key", Duration.ofSeconds(10), 0, List.of(), StdioIsolationProperties.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
