// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.server;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpGovernedDecisionServiceTest {

    @Test
    void mutations_are_disabled_by_default() {
        var beans = new StaticListableBeanFactory();
        var service = new McpGovernedDecisionService(beans.getBeanProvider(com.kex.agent.supervision.SupervisionService.class),
                new KexMcpServerProperties(true, Set.of(), 120, false, java.time.Duration.ofMinutes(30), java.time.Duration.ofMinutes(5)));

        assertThatThrownBy(() -> service.approve("decision-1", "operator"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Governed MCP mutations are disabled");
    }
}
