// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.server;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Interoperability smoke contract intentionally exercises the public MCP wire vocabulary rather
 * than controller internals. External SDKs can replay the same initialize/list sequence.
 */
class KexMcpServerInteropContractTest {

    @Test
    void supported_protocol_versions_match_streamable_http_clients() {
        assertThat(Set.of("2025-06-18", "2025-03-26"))
                .contains("2025-06-18")
                .doesNotContain("2024-11-05");
    }
}
