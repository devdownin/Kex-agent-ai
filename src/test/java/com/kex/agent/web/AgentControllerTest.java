package com.kex.agent.web;

import com.kex.agent.agent.AgentAnswer;
import com.kex.agent.agent.AgentService;
import com.kex.agent.mcp.McpServerInfo;
import com.kex.agent.mcp.McpToolCatalog;
import com.kex.agent.mcp.McpToolInfo;
import com.kex.agent.mcp.McpToolResult;
import com.kex.agent.mcp.UnknownMcpServerException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
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

    @Test
    void appelle_un_outil_mcp_directement() {
        given(toolCatalog.call("filesystem", "read_file", Map.of("path", "/tmp/a.txt")))
                .willReturn(new McpToolResult("filesystem", "read_file", false, List.of("contenu"), null));

        var response = mvc.post().uri("/api/agent/mcp/servers/filesystem/tools/read_file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"arguments":{"path":"/tmp/a.txt"}}""");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.error").isEqualTo(false);
        assertThat(response).bodyJson().extractingPath("$.content[0]").isEqualTo("contenu");
    }

    @Test
    void accepte_un_appel_sans_arguments() {
        given(toolCatalog.call("filesystem", "list_roots", Map.of()))
                .willReturn(new McpToolResult("filesystem", "list_roots", false, List.of("/tmp"), null));

        assertThat(mvc.post().uri("/api/agent/mcp/servers/filesystem/tools/list_roots")).hasStatusOk();
    }

    @Test
    void retourne_404_sur_serveur_mcp_inconnu() {
        willThrow(new UnknownMcpServerException("absent"))
                .given(toolCatalog).call("absent", "read_file", Map.of());

        assertThat(mvc.post().uri("/api/agent/mcp/servers/absent/tools/read_file")).hasStatus(404);
    }
}
