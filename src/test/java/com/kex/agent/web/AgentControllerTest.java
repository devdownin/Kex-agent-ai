// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.web;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.kex.agent.agent.AgentAnswer;
import com.kex.agent.agent.AgentService;
import com.kex.agent.agent.AgentStream;
import com.kex.agent.agent.AgentTimeoutException;
import com.kex.agent.mcp.McpResourceContent;
import com.kex.agent.mcp.McpResourceInfo;
import com.kex.agent.mcp.McpServerInfo;
import com.kex.agent.mcp.McpServerUnavailableException;
import com.kex.agent.mcp.McpToolCatalog;
import com.kex.agent.mcp.McpToolInfo;
import com.kex.agent.mcp.McpToolResult;
import com.kex.agent.mcp.UnknownMcpServerException;
import com.kex.agent.mcp.UnsupportedMcpCapabilityException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@WebMvcTest(AgentController.class)
// Sécurité désactivée ici : elle a son propre test (ApiSecurityTest), ces cas visent le contrôleur.
@AutoConfigureMockMvc(addFilters = false)
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
                new McpServerInfo("kafka-explorer", "kafka-explorer-mcp", "0.1.0", "2025-06-18", true,
                        List.of(new McpToolInfo("kex_list_topics", "Liste les topics")))));

        var response = mvc.get().uri("/api/agent/mcp/servers");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$[0].connection").isEqualTo("kafka-explorer");
        assertThat(response).bodyJson().extractingPath("$[0].serverName").isEqualTo("kafka-explorer-mcp");
        assertThat(response).bodyJson().extractingPath("$[0].tools[0].name").isEqualTo("kex_list_topics");
    }

    @Test
    void appelle_un_outil_mcp_directement() {
        given(toolCatalog.call("kafka-explorer", "kex_list_topics", Map.of("prefix", "demo.")))
                .willReturn(new McpToolResult("kafka-explorer", "kex_list_topics", false, List.of("demo.orders"), null));

        var response = mvc.post().uri("/api/agent/mcp/servers/kafka-explorer/tools/kex_list_topics")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"arguments":{"prefix":"demo."}}""");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.error").isEqualTo(false);
        assertThat(response).bodyJson().extractingPath("$.content[0]").isEqualTo("demo.orders");
    }

    @Test
    void accepte_un_appel_sans_arguments() {
        given(toolCatalog.call("kafka-explorer", "kex_list_topics", Map.of()))
                .willReturn(new McpToolResult("kafka-explorer", "kex_list_topics", false, List.of("demo.orders"), null));

        assertThat(mvc.post().uri("/api/agent/mcp/servers/kafka-explorer/tools/kex_list_topics")).hasStatusOk();
    }

    @Test
    void retourne_404_sur_serveur_mcp_inconnu() {
        willThrow(new UnknownMcpServerException("absent"))
                .given(toolCatalog).call("absent", "kex_list_topics", Map.of());

        assertThat(mvc.post().uri("/api/agent/mcp/servers/absent/tools/kex_list_topics")).hasStatus(404);
    }

    @Test
    void liste_les_ressources_d_un_serveur() {
        given(toolCatalog.resources("kafka-explorer")).willReturn(List.of(
                new McpResourceInfo("kafka://cluster/topics", "topics", null, "application/json", null)));

        var response = mvc.get().uri("/api/agent/mcp/servers/kafka-explorer/resources");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$[0].uri").isEqualTo("kafka://cluster/topics");
    }

    @Test
    void lit_une_ressource() {
        given(toolCatalog.readResource("kafka-explorer", "kafka://cluster/topics")).willReturn(List.of(
                new McpResourceContent("kafka://cluster/topics", "application/json", "[]", null)));

        var response = mvc.get().uri("/api/agent/mcp/servers/kafka-explorer/resource?uri={uri}",
                "kafka://cluster/topics");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$[0].text").isEqualTo("[]");
    }

    @Test
    void retourne_501_si_le_serveur_n_expose_pas_de_ressources() {
        willThrow(new UnsupportedMcpCapabilityException("filesystem", "resources"))
                .given(toolCatalog).readResource("filesystem", "file:///a");

        assertThat(mvc.get().uri("/api/agent/mcp/servers/filesystem/resource?uri={uri}", "file:///a"))
                .hasStatus(501);
    }

    @Test
    void retourne_503_si_le_serveur_mcp_est_injoignable() {
        willThrow(new McpServerUnavailableException("kafka-explorer", new IllegalStateException("refused")))
                .given(toolCatalog).call("kafka-explorer", "kex_list_topics", Map.of());

        assertThat(mvc.post().uri("/api/agent/mcp/servers/kafka-explorer/tools/kex_list_topics")).hasStatus(503);
    }

    @Test
    void le_flux_annonce_la_conversation_puis_les_tokens() throws Exception {
        given(agentService.stream(null, "bonjour"))
                .willReturn(new AgentStream("conv-9", Flux.just("sa", "lut")));

        var response = mvc.post().uri("/api/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"message":"bonjour"}""")
                .exchange();

        assertThat(response).hasStatusOk();
        String body = response.getResponse().getContentAsString();
        assertThat(body).contains("event:conversation").contains("data:conv-9")
                .contains("event:token").contains("data:sa").contains("data:lut");
    }

    @Test
    void le_flux_emet_un_evenement_error_au_lieu_de_se_taire() throws Exception {
        given(agentService.stream("conv-1", "bonjour")).willReturn(new AgentStream("conv-1",
                Flux.error(new AgentTimeoutException(Duration.ofSeconds(120)))));

        var response = mvc.post().uri("/api/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"conversationId":"conv-1","message":"bonjour"}""")
                .exchange();

        assertThat(response).hasStatusOk();
        assertThat(response.getResponse().getContentAsString())
                .contains("event:error").contains("120s");
    }

    @Test
    void retourne_504_quand_l_appel_bloquant_depasse_le_plafond() {
        willThrow(new AgentTimeoutException(Duration.ofSeconds(120)))
                .given(agentService).ask("conv-1", "bonjour");

        assertThat(mvc.post().uri("/api/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"conversationId":"conv-1","message":"bonjour"}"""))
                .hasStatus(504);
    }
}
