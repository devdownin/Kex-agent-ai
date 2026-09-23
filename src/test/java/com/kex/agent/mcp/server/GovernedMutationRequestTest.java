// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.server;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GovernedMutationRequestTest {

    @Test
    void mutation_contract_is_reserved_but_disabled_by_default() {
        var properties = new KexMcpServerProperties(true, Set.of(), 120, false);

        assertThat(properties.governedMutationsEnabled()).isFalse();
        assertThat(GovernedMutationRequest.RESERVED_TOOL_NAMES)
                .containsExactly("kex_approve_decision", "kex_reject_decision");
        assertThat(GovernedMutationRequest.INPUT_SCHEMA.get("additionalProperties")).isEqualTo(false);
    }
}
