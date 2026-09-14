package com.kex.agent.web;

import com.kex.agent.agent.AgentAnswer;
import com.kex.agent.agent.AgentService;
import com.kex.agent.mcp.McpServerInfo;
import com.kex.agent.mcp.McpToolCatalog;
import com.kex.agent.mcp.McpToolInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@WebMvcTest(AgentController.class)
class AgentControllerTest {

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    AgentService agentService;

    @MockitoBean
    McpToolCatalog toolCatalog;

    @Test
    void repond_avec_le_contenu_de_l_agent() {
        given(agentService.ask("conv-1", "bonjour")).willReturn(new AgentAnswer("conv-1", "salut"));

        var response = mvc.post().uri("/api/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"conversationId":"conv-1","message":"bonjour"}""");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.conversationId").isEqualTo("conv-1");
        assertThat(response).bodyJson().extractingPath("$.content").isEqualTo("salut");
    }

    @Test
    void rejette_un_message_vide() {
        assertThat(mvc.post().uri("/api/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"conversationId":"conv-1","message":"  "}"""))
                .hasStatus(400);
    }

    @Test
    void purge_une_conversation() {
        assertThat(mvc.delete().uri("/api/agent/conversations/conv-1")).hasStatus(204);

        verify(agentService).clear("conv-1");
    }

    @Test
    void liste_les_serveurs_mcp() {
        given(toolCatalog.servers()).willReturn(List.of(
                new McpServerInfo("filesystem", "1.0.0", "2025-06-18", true,
                        List.of(new McpToolInfo("read_file", "Lit un fichier")))));

        var response = mvc.get().uri("/api/agent/mcp/servers");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$[0].name").isEqualTo("filesystem");
        assertThat(response).bodyJson().extractingPath("$[0].tools[0].name").isEqualTo("read_file");
    }
}
