// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryIdentityTest {

    @Test
    void extracts_owner_from_tool_context() {
        ToolContext context = new ToolContext(Map.of(MemoryIdentity.OWNER_CONTEXT_KEY, "user1"));
        assertThat(MemoryIdentity.from(context)).isEqualTo("user1");

        ToolContext empty = new ToolContext(Map.of());
        assertThat(MemoryIdentity.from(empty)).isEqualTo("kex-internal");
    }

    @Test
    void validates_require_owner() {
        assertThat(MemoryIdentity.require("admin")).isEqualTo("admin");

        assertThatThrownBy(() -> MemoryIdentity.require(""))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> MemoryIdentity.require("a".repeat(300)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
