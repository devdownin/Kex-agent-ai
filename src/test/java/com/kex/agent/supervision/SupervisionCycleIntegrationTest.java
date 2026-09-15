// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import com.kex.agent.agent.AgentService;
import com.kex.agent.agent.AgentStructuredAnswer;
import com.kex.agent.mcp.FakeMcpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * Le cycle de supervision de bout en bout : contexte Spring réel, politique liée à un vrai serveur
 * MCP parlant le transport streamable-HTTP, et une action qui part effectivement sur le réseau.
 *
 * <p>Seul le modèle est simulé, et c'est délibéré : {@code askStructured} appelle un fournisseur
 * distant payant, non déterministe, qu'aucune CI ne doit joindre. Ce qui est vérifié ici est tout
 * le reste — la politique, le passage de la décision à l'exécution, l'appel MCP réel, le résultat
 * rapporté et l'audit — c'est-à-dire précisément ce que les tests unitaires simulent.
 *
 * <p>Le service est un singleton du contexte : son historique survit d'un cas à l'autre. Chaque
 * assertion est donc rattachée au cycle qu'elle vient de lancer, plutôt que de dépendre d'un ordre
 * d'exécution que JUnit ne garantit pas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("mcp-it")
class SupervisionCycleIntegrationTest {

    private static final FakeMcpServer SERVER = start();

    @Autowired
    SupervisionService supervision;

    /** Le seul point simulé : la CI ne joint pas un fournisseur de modèle. */
    @MockitoBean
    AgentService agentService;

    private static FakeMcpServer start() {
        try {
            return new FakeMcpServer();
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String url = "http://127.0.0.1:" + SERVER.port();
        registry.add("spring.ai.mcp.client.streamable-http.connections.faux.url", () -> url);
        registry.add("spring.ai.mcp.client.streamable-http.connections.faux.endpoint", () -> "/mcp");
        registry.add("kex.mcp.bearer-tokens[0].url-prefix", () -> url);
        registry.add("kex.mcp.bearer-tokens[0].token", () -> FakeMcpServer.TOKEN);

        registry.add("kex.agent.supervision.processes[0].id", () -> "order-integration");
        registry.add("kex.agent.supervision.processes[0].name", () -> "Order Integration");
        registry.add("kex.agent.supervision.mode", () -> "AUTOMATIC");
        registry.add("kex.agent.supervision.autonomy.RESTART_CONSUMER", () -> "AUTOMATIC");
        registry.add("kex.agent.supervision.actions.RESTART_CONSUMER.connection", () -> "faux");
        registry.add("kex.agent.supervision.actions.RESTART_CONSUMER.tool", () -> "echo");
    }

    @AfterAll
    static void stop() {
        SERVER.close();
    }

    @Test
    void un_cycle_mene_l_anomalie_jusqu_a_un_appel_d_outil_reel() {
        given(agentService.askStructured(anyString(), anyString(), any()))
                .willReturn(new AgentStructuredAnswer("cycle", analysis(), List.of()));

        CycleReport report = supervision.runCycle("test-integration");

        assertThat(report.failure()).isNull();
        assertThat(report.processesAnalysed()).isEqualTo(1);
        assertThat(report.anomaliesDetected()).isEqualTo(1);

        // L'action est partie sur le réseau : c'est « pong », la réponse du serveur, qui remonte.
        assertThat(decisionsOf(report)).singleElement().satisfies(decision -> {
            assertThat(decision.status()).isEqualTo(DecisionStatus.EXECUTED);
            assertThat(decision.result()).isEqualTo("pong");
            assertThat(decision.resolvedBy()).isEqualTo("Agent");
            assertThat(decision.policyVersion()).isEqualTo("policy-v1");
        });
        assertThat(SERVER.methods()).contains("tools/call");
        // Le bearer MCP est injecté jusque dans l'exécution d'une action, pas seulement au handshake.
        assertThat(SERVER.unauthorizedCount()).isZero();

        // Le relevé du modèle a bien atterri dans l'état des processus.
        assertThat(supervision.snapshots()).singleElement()
                .extracting(ProcessSnapshot::state).isEqualTo(ProcessState.WARNING);

        // Et l'audit permet de reconstituer la situation.
        assertThat(supervision.audit()).anySatisfy(entry -> {
            assertThat(entry.actor()).isEqualTo("Agent");
            assertThat(entry.processId()).isEqualTo("order-integration");
            assertThat(entry.result()).contains("EXECUTED");
        });

        // La conversation du cycle est jetable : elle est purgée, pas laissée à grossir.
        org.mockito.Mockito.verify(agentService).clear("supervision-" + report.id());
    }

    @Test
    void une_capacite_sans_outil_lie_echoue_sans_toucher_au_reseau() {
        // MODIFY_CONFIGURATION n'est lié à aucun outil : l'échec doit le dire, pas partir à moitié.
        given(agentService.askStructured(anyString(), anyString(), any()))
                .willReturn(new AgentStructuredAnswer("cycle", analysis("MODIFY_CONFIGURATION"), List.of()));

        CycleReport report = supervision.runCycle("test-integration");

        assertThat(decisionsOf(report)).singleElement().satisfies(decision -> {
            // Interdite par défaut : non citée dans la politique, donc recommandation seule.
            assertThat(decision.status()).isEqualTo(DecisionStatus.BLOCKED);
            assertThat(decision.result()).contains("interdite par la politique");
        });
    }

    private List<Decision> decisionsOf(CycleReport report) {
        return supervision.decisions().stream()
                .filter(decision -> report.id().equals(decision.cycleId()))
                .toList();
    }

    private static Map<String, Object> analysis() {
        return analysis("RESTART_CONSUMER");
    }

    private static Map<String, Object> analysis(String capability) {
        return Map.of(
                "processes", List.of(Map.of("processId", "order-integration", "state", "WARNING",
                        "delayMillis", 480_000, "note", "Retard de 8 minutes")),
                "anomalies", List.of(Map.of(
                        "processId", "order-integration",
                        "title", "Retard de traitement",
                        "severity", "WARNING",
                        "observations", List.of(Map.of("label", "Consumer lag", "value", "12421")),
                        "analysis", "Consommation plus lente que la production",
                        "confidence", 0.97,
                        "recommendation", "Redémarrer Consumer Integration-02",
                        "capability", capability)));
    }
}
