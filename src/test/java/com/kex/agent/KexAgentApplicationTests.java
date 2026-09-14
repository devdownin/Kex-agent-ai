package com.kex.agent;

import com.kex.agent.agent.AgentService;
import com.kex.agent.mcp.McpToolCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.observation.ChatModelMeterObservationHandler;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
}
