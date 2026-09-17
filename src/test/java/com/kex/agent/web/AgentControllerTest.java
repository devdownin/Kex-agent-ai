// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.web;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.anthropic.errors.AnthropicException;
import com.kex.agent.agent.AgentAnswer;
import com.kex.agent.agent.AgentEvent;
import com.kex.agent.agent.AgentService;
import com.kex.agent.agent.AgentStream;
import com.kex.agent.agent.AgentStructuredAnswer;
import com.kex.agent.agent.AgentTimeoutException;
import com.kex.agent.agent.StructuredOutputException;
import com.kex.agent.mcp.McpResourceContent;
import com.kex.agent.mcp.McpResourceInfo;
import com.kex.agent.mcp.McpServerInfo;
import com.kex.agent.mcp.McpServerUnavailableException;
import com.kex.agent.mcp.McpToolCatalog;
import com.kex.agent.mcp.McpToolInfo;
import com.kex.agent.mcp.McpToolMetric;
import com.kex.agent.mcp.McpToolResult;
import com.kex.agent.mcp.UnknownMcpServerException;
import com.kex.agent.mcp.UnsupportedMcpCapabilityException;
import com.kex.agent.supervision.SupervisionService;
import com.openai.errors.OpenAIException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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

    @MockitoBean
    SupervisionService supervision;

    @Test
    void repond_avec_le_contenu_de_l_agent() {
        given(agentService.ask("conv-1", "bonjour")).willReturn(new AgentAnswer("conv-1", "salut", List.of(), null, "end_turn"));

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
                new McpServerInfo("kafka-explorer", "kafka-explorer-mcp", "0.1.0", "2025-06-18", true, "CLOSED",
                        List.of(new McpToolInfo("kex_list_topics", "Liste les topics", Map.of("type", "object"))))));

        var response = mvc.get().uri("/api/agent/mcp/servers");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$[0].connection").isEqualTo("kafka-explorer");
        assertThat(response).bodyJson().extractingPath("$[0].serverName").isEqualTo("kafka-explorer-mcp");
        assertThat(response).bodyJson().extractingPath("$[0].tools[0].name").isEqualTo("kex_list_topics");
    }

    @Test
    void liste_les_metriques_par_outil() {
        given(toolCatalog.metrics()).willReturn(List.of(
                new McpToolMetric("kafka-explorer", "kex_list_topics", 3, 12.5)));

        var response = mvc.get().uri("/api/agent/mcp/metrics");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$[0].tool").isEqualTo("kex_list_topics");
        assertThat(response).bodyJson().extractingPath("$[0].callCount").isEqualTo(3);
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

    /**
     * Cette route agit sans décision ni politique de supervision à vérifier : l'audit est la seule
     * trace de qui a appelé quoi, au même titre que la suppression d'un souvenir (MemoryController).
     */
    @Test
    void trace_l_invocation_directe_dans_l_audit() {
        given(toolCatalog.call("kafka-explorer", "kex_list_topics", Map.of("prefix", "demo.")))
                .willReturn(new McpToolResult("kafka-explorer", "kex_list_topics", false, List.of("demo.orders"), null));

        mvc.post().uri("/api/agent/mcp/servers/kafka-explorer/tools/kex_list_topics")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"arguments":{"prefix":"demo."}}""")
                .exchange();

        verify(supervision).auditAction("Anonyme", "Appel MCP direct : kex_list_topics sur kafka-explorer",
                "Exécuté");
    }

    @Test
    void trace_l_echec_d_une_invocation_directe_dans_l_audit() {
        willThrow(new McpServerUnavailableException("kafka-explorer", new IllegalStateException("refused")))
                .given(toolCatalog).call("kafka-explorer", "kex_list_topics", Map.of());

        mvc.post().uri("/api/agent/mcp/servers/kafka-explorer/tools/kex_list_topics").exchange();

        verify(supervision).auditAction(eq("Anonyme"), eq("Appel MCP direct : kex_list_topics sur kafka-explorer"),
                contains("refused"));
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
        given(agentService.stream(null, "bonjour")).willReturn(new AgentStream("conv-9",
                Flux.just(new AgentEvent.Token("sa"), new AgentEvent.ToolCall("echo", 12, false),
                        new AgentEvent.Token("lut"))));

        var response = mvc.post().uri("/api/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"message":"bonjour"}""")
                .exchange();

        assertThat(response).hasStatusOk();
        String body = response.getResponse().getContentAsString();
        assertThat(body).contains("event:conversation").contains("data:conv-9")
                .contains("event:token").contains("data:sa").contains("data:lut")
                // Sans cet événement, le flux reste muet pendant l'exécution de l'outil.
                .contains("event:tool").contains("\"tool\":\"echo\"").contains("\"durationMillis\":12");
    }

    @Test
    void le_flux_emet_un_evenement_error_au_lieu_de_se_taire() throws Exception {
        given(agentService.stream("conv-1", "bonjour")).willReturn(new AgentStream("conv-1",
                Flux.error(new AgentTimeoutException(Duration.ofSeconds(120), "conv-1"))));

        var response = mvc.post().uri("/api/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"conversationId":"conv-1","message":"bonjour"}""")
                .exchange();

        assertThat(response).hasStatusOk();
        assertThat(response.getResponse().getContentAsString())
                .contains("event:error").contains("120s");
    }

    /**
     * L'échange continue en arrière-plan et sa réponse tardive atterrira dans cette conversation :
     * sans l'identifiant dans le corps, un appelant qui n'en avait pas fourni — le premier message
     * de la console — ne pourrait ni la reprendre ni la purger, alors que son message y est écrit.
     */
    /** La même panne ne se raconte pas de deux façons selon la route empruntée. */
    @Test
    void le_flux_dit_le_disjoncteur_ouvert_comme_le_chemin_bloquant() throws Exception {
        given(agentService.stream("conv-1", "bonjour")).willReturn(new AgentStream("conv-1",
                Flux.error(CallNotPermittedException.createCallNotPermittedException(
                        CircuitBreaker.ofDefaults("agent-model")))));

        var response = mvc.post().uri("/api/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"conversationId":"conv-1","message":"bonjour"}""")
                .exchange();

        assertThat(response).hasStatusOk();
        assertThat(response.getResponse().getContentAsString())
                .contains("event:error").contains("nouvel essai dans quelques instants");
    }

    @Test
    void retourne_504_avec_l_identifiant_de_conversation_quand_l_appel_depasse_le_plafond() {
        willThrow(new AgentTimeoutException(Duration.ofSeconds(120), "conv-9"))
                .given(agentService).ask("conv-1", "bonjour");

        var response = mvc.post().uri("/api/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"conversationId":"conv-1","message":"bonjour"}""");

        assertThat(response).hasStatus(504);
        assertThat(response).bodyJson().extractingPath("$.conversationId").isEqualTo("conv-9");
    }

    @Test
    void rend_une_sortie_structuree() {
        given(agentService.askStructured("conv-1", "combien de topics ?", Map.of("type", "object")))
                .willReturn(new AgentStructuredAnswer("conv-1", Map.of("total", 8), List.of(), null, "end_turn"));

        var response = mvc.post().uri("/api/agent/chat/structured")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"conversationId":"conv-1","message":"combien de topics ?",
                         "schema":{"type":"object"}}""");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.content.total").isEqualTo(8);
    }

    @Test
    void rejette_une_sortie_structuree_sans_schema() {
        assertThat(mvc.post().uri("/api/agent/chat/structured")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"message":"bonjour","schema":{}}"""))
                .hasStatus(400);
    }

    @Test
    void retourne_502_quand_le_modele_ne_respecte_pas_le_schema() {
        willThrow(new StructuredOutputException(new IllegalStateException("pas du json")))
                .given(agentService).askStructured(null, "bonjour", Map.of("type", "object"));

        assertThat(mvc.post().uri("/api/agent/chat/structured")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"message":"bonjour","schema":{"type":"object"}}"""))
                .hasStatus(502);
    }

    /**
     * Sans ce handler, une clé Anthropic absente ou refusée remontait non attrapée et se
     * traduisait en 401 — le même code que notre propre bearer rejeté. Un appelant au jeton
     * kex.agent.api-key parfaitement valide se voyait répondre comme si ce jeton-là était en
     * cause, sans aucun moyen de distinguer les deux.
     */
    @Test
    void retourne_502_et_non_401_quand_le_fournisseur_anthropic_refuse_la_cle() {
        willThrow(new AnthropicException("invalid x-api-key"))
                .given(agentService).ask("conv-1", "bonjour");

        assertThat(mvc.post().uri("/api/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"conversationId":"conv-1","message":"bonjour"}"""))
                .hasStatus(502).bodyJson().extractingPath("$.detail").isEqualTo("invalid x-api-key");
    }

    /** Même défaut, même correctif, côté OpenRouter — l'autre fournisseur que l'agent sait appeler. */
    @Test
    void retourne_502_et_non_401_quand_la_passerelle_openai_refuse_la_cle() {
        willThrow(new OpenAIException("invalid api key"))
                .given(agentService).askStructured("conv-1", "bonjour", Map.of("type", "object"));

        assertThat(mvc.post().uri("/api/agent/chat/structured")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"conversationId":"conv-1","message":"bonjour","schema":{"type":"object"}}"""))
                .hasStatus(502);
    }

    /**
     * Le disjoncteur a ouvert : échouer tout de suite en 503 plutôt que de laisser l'appelant
     * attendre le plafond de temps pour redécouvrir une panne déjà constatée.
     */
    @Test
    void retourne_503_quand_le_disjoncteur_du_modele_est_ouvert() {
        willThrow(CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("agent-model")))
                .given(agentService).ask("conv-1", "bonjour");

        assertThat(mvc.post().uri("/api/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"conversationId":"conv-1","message":"bonjour"}"""))
                .hasStatus(503);
    }

    @Test
    void expose_les_outils_utilises_par_une_reponse() {
        given(agentService.ask("conv-1", "bonjour")).willReturn(new AgentAnswer("conv-1", "salut",
                List.of(new AgentEvent.ToolCall("kex_list_topics", 42, false)), null, "end_turn"));

        var response = mvc.post().uri("/api/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"conversationId":"conv-1","message":"bonjour"}""");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.tools[0].tool").isEqualTo("kex_list_topics");
    }
}
