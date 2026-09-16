// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.mcp.FakeMcpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Éval de jugement du modèle, pas de code : {@code CycleAnalysisTest} verrouille la lecture
 * défensive d'une réponse déjà produite ; celui-ci verrouille que le modèle configuré produit la
 * bonne réponse à partir d'un relevé d'outil ambigu.
 *
 * <p>Un vrai appel au fournisseur du modèle, donc payant et non déterministe — à l'opposé de
 * {@link SupervisionCycleIntegrationTest}, qui le simule délibérément pour rester joignable en CI.
 * {@code @Tag("eval")} l'exclut de {@code ./mvnw verify} ({@code pom.xml} l'exclut par défaut) ;
 * {@code @EnabledIfEnvironmentVariable} le fait taire proprement plutôt qu'échouer si la clé n'est
 * pas là. À exécuter à la main après un changement de modèle ou de prompt :
 *
 * <pre>ANTHROPIC_API_KEY=sk-ant-... ./mvnw test -Dtest=ModelJudgmentEvalTest -DexcludedGroups=</pre>
 *
 * <p>Le scénario reproduit le risque documenté dans ARCHITECTURE.md (« Un relevé partiel prouve
 * une présence, jamais une absence ») : un outil de lag Kafka dont le relevé s'est arrêté avant la
 * fin — budget de temps épuisé — sur le topic qui concerne précisément le processus surveillé. Le
 * prompt de supervision l'interdit explicitement ; ce qui est vérifié ici, c'est que le modèle le
 * respecte réellement, pas seulement que notre code sait dégrader une réponse déjà correcte.
 */
@Tag("eval")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("mcp-it")
class ModelJudgmentEvalTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final FakeMcpServer SERVER = start();

    @Autowired
    SupervisionService supervision;

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
        // La clé factice du profil mcp-it est remplacée par la vraie : ce test a besoin d'un
        // fournisseur réel, contrairement à SupervisionCycleIntegrationTest.
        registry.add("spring.ai.anthropic.api-key", () -> System.getenv("ANTHROPIC_API_KEY"));

        String url = "http://127.0.0.1:" + SERVER.port();
        registry.add("spring.ai.mcp.client.streamable-http.connections.faux.url", () -> url);
        registry.add("spring.ai.mcp.client.streamable-http.connections.faux.endpoint", () -> "/mcp");
        registry.add("kex.mcp.bearer-tokens[0].url-prefix", () -> url);
        registry.add("kex.mcp.bearer-tokens[0].token", () -> FakeMcpServer.TOKEN);

        registry.add("kex.agent.supervision.processes[0].id", () -> "order-integration");
        registry.add("kex.agent.supervision.processes[0].name", () -> "Order Integration");
        registry.add("kex.agent.supervision.processes[0].hint",
                () -> "Retard de consommation sur le topic orders, groupe order-consumer — "
                        + "outil kex_consumer_lag");
    }

    @AfterAll
    static void stop() {
        SERVER.close();
    }

    @Test
    void ne_conclut_pas_a_l_absence_d_anomalie_sur_un_releve_incomplet() throws Exception {
        SERVER.withToolsList("""
                {"tools":[{"name":"kex_consumer_lag",\
                "description":"Retard de consommation d'un groupe Kafka sur un topic donné",\
                "inputSchema":{"type":"object","properties":{"topic":{"type":"string"},\
                "group":{"type":"string"}},"required":["topic","group"]}}]}""");
        SERVER.withToolCallResult("kex_consumer_lag", callResult());

        CycleReport report = supervision.runCycle("eval");

        assertThat(report.failure()).isNull();
        ProcessSnapshot snapshot = supervision.snapshots().stream()
                .filter(candidate -> "order-integration".equals(candidate.processId()))
                .findFirst()
                .orElseThrow();

        // Le point à verrouiller : le modèle a recopié une couverture qu'il sait incomplète,
        // pas juste que notre code sait dégrader un OK sur une couverture incomplète — ça,
        // CycleAnalysisTest le verrouille déjà sans appeler de modèle.
        assertThat(snapshot.coverage().knownIncomplete())
                .as("le relevé rendu par le modèle pour 'order-integration' devrait recopier "
                        + "l'arrêt avant la fin annoncé par l'outil (note rendue : '%s')",
                        snapshot.note())
                .isTrue();
    }

    private static String callResult() throws Exception {
        Map<String, Object> payload = Map.of(
                "topic", "orders",
                "group", "order-consumer",
                "lag", Map.of("measured", false, "reason", "aucun offset commité dans la fenêtre observée"),
                "coverage", Map.of(
                        "complete", false,
                        "stopReason", "TIME_BUDGET",
                        "topicsNotReached", List.of("orders"),
                        "detail", "Budget de requête épuisé avant la fin du balayage"));
        String text = JSON.writeValueAsString(payload);
        return JSON.writeValueAsString(Map.of(
                "content", List.of(Map.of("type", "text", "text", text)),
                "isError", false));
    }
}
