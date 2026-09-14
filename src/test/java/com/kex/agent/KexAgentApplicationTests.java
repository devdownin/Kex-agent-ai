// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent;

import java.util.Map;

import com.kex.agent.agent.AgentService;
import com.kex.agent.agent.ToolCallRecorder;
import com.kex.agent.mcp.McpToolCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.observation.ChatModelMeterObservationHandler;
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Démarre sans serveur MCP configuré : le catalogue doit être vide, pas absent. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class KexAgentApplicationTests {

    @Autowired
    ChatClient chatClient;

    @Autowired
    AgentService agentService;

    @Autowired
    McpToolCatalog toolCatalog;

    @Autowired
    ApplicationContext context;

    @Autowired
    ToolContextToMcpMetaConverter metaConverter;

    @Test
    void charge_le_contexte_sans_serveur_mcp() {
        assertThat(chatClient).isNotNull();
        assertThat(agentService).isNotNull();
        assertThat(toolCatalog.servers()).isEmpty();
    }

    @Test
    void compte_les_jetons_consommes() {
        // Spring AI n'enregistre ce handler que si un MeterRegistry est présent : sans lui, le coût
        // de chaque échange ne serait mesuré nulle part.
        assertThat(context.getBeanNamesForType(ChatModelMeterObservationHandler.class)).isNotEmpty();
    }

    @Test
    void n_envoie_pas_le_contexte_interne_au_serveur_mcp() {
        // Le convertisseur par défaut de Spring AI recopie tout le ToolContext dans le _meta de
        // chaque appel d'outil : le collecteur d'événements, non sérialisable, partirait sur le
        // réseau. Les clés internes sont filtrées, les autres doivent passer.
        Map<String, Object> meta = metaConverter.convert(new ToolContext(Map.of(
                ToolCallRecorder.CONTEXT_KEY, new ToolCallRecorder(),
                "exchange", "interne",
                "tenant", "acme")));

        assertThat(meta).containsExactly(org.assertj.core.api.Assertions.entry("tenant", "acme"));
    }
}
