// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import com.kex.agent.agent.AgentService;
import com.kex.agent.agent.ToolCallRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryToolsTest {

    private final MemoryTools tools = new MemoryTools(new MemoryService(new InMemoryMemoryRepository(200),
            new MemoryProperties(true, 200, 500, Duration.ofDays(30)),
            Clock.fixed(Instant.parse("2026-09-15T08:00:00Z"), ZoneOffset.UTC)));

    @Test
    void retient_via_un_outil_et_relit_via_l_autre() {
        ToolContext context = new ToolContext(Map.of(AgentService.CONVERSATION_ID_CONTEXT_KEY, "conv-1"));

        tools.rememberFact("kafka-explorer expose kex_list_topics", context);

        assertThat(tools.recallFacts(new ToolContext(Map.of())))
                .containsExactly("kafka-explorer expose kex_list_topics");
    }

    @Test
    void fonctionne_sans_identite_de_conversation() {
        ToolContext context = new ToolContext(Map.of());

        String result = tools.rememberFact("fait sans conversation identifiée", context);

        assertThat(result).isEqualTo("Retenu.");
        assertThat(tools.recallFacts(context)).containsExactly("fait sans conversation identifiée");
    }

    @Test
    void chronometre_l_appel_quand_un_collecteur_est_present() {
        ToolCallRecorder recorder = new ToolCallRecorder();
        ToolContext context = new ToolContext(Map.of(ToolCallRecorder.CONTEXT_KEY, recorder));

        tools.rememberFact("fait chronométré", context);

        assertThat(recorder.calls()).singleElement().satisfies(call -> {
            assertThat(call.tool()).isEqualTo("remember_fact");
            assertThat(call.failed()).isFalse();
        });
    }
}
