package com.kex.agent;

import com.kex.agent.agent.AgentService;
import com.kex.agent.mcp.McpToolCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
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

    @Test
    void charge_le_contexte_sans_serveur_mcp() {
        assertThat(chatClient).isNotNull();
        assertThat(agentService).isNotNull();
        assertThat(toolCatalog.servers()).isEmpty();
    }
}
