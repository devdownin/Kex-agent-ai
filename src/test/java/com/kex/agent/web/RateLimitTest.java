// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.web;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.stream.IntStream;

import com.kex.agent.agent.AgentAnswer;
import com.kex.agent.agent.AgentService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "kex.agent.api-key=secret",
        "kex.agent.api-keys.ops-console=jeton-ops",
        "kex.agent.api-keys.ci-pipeline=jeton-ci",
        // Clé dédiée : les autres tests de cette classe partagent le même contexte Spring, donc le
        // même seau par principal — réutiliser "secret" ou "jeton-ops" ici dépendrait de l'ordre
        // d'exécution des méthodes.
        "kex.agent.api-keys.mcp-console=jeton-mcp",
        "kex.agent.rate-limit.enabled=true",
        // Débit lent : le seau ne doit pas se recharger pendant la rafale du test.
        "kex.agent.rate-limit.requests-per-minute=1",
        "kex.agent.rate-limit.burst=3"
})
@ActiveProfiles("test")
class RateLimitTest {

    @LocalServerPort
    int port;

    @MockitoBean
    AgentService agentService;

    @Test
    void refuse_au_dela_de_la_pointe_autorisee() throws Exception {
        given(agentService.ask(any(), anyString())).willReturn(new AgentAnswer("conv-1", "ok", java.util.List.of()));

        List<Integer> codes = IntStream.range(0, 5).mapToObj(i -> chat("Bearer secret")).toList();

        assertThat(codes).startsWith(200, 200, 200).endsWith(429, 429);
    }

    /**
     * Deux clés nommées, deux seaux : une clé qui a épuisé le sien ne doit rien retirer au budget
     * d'une autre, faute de quoi un pipeline CI en boucle affamerait la console d'un opérateur.
     */
    @Test
    void isole_le_debit_entre_deux_clefs_nommees() throws Exception {
        given(agentService.ask(any(), anyString())).willReturn(new AgentAnswer("conv-1", "ok", java.util.List.of()));

        List<Integer> ci = IntStream.range(0, 5).mapToObj(i -> chat("Bearer jeton-ci")).toList();
        assertThat(ci).startsWith(200, 200, 200).endsWith(429, 429);

        // La clé de la console n'a encore rien consommé : elle garde son plein débit.
        assertThat(chat("Bearer jeton-ops")).isEqualTo(200);
    }

    @Test
    void ne_limite_pas_l_introspection_mcp() throws Exception {
        // Le quota du chat ne doit pas couper la supervision, qui ne coûte pas de jetons.
        for (int i = 0; i < 5; i++) {
            chat("Bearer secret");
        }
        assertThat(get("/api/agent/mcp/servers")).isEqualTo(200);
    }

    /**
     * Contrairement à l'introspection (GET), cette route déclenche un vrai traitement côté serveur
     * MCP à chaque appel : sans ce test, un caller authentifié pourrait la marteler sans aucun
     * débit, y compris sur un serveur MCP inconnu du test (d'où le 404 attendu ici, pas un 200 —
     * seul le comptage du débit est en jeu).
     */
    @Test
    void limite_l_invocation_directe_d_un_outil_mcp() throws Exception {
        List<Integer> codes = IntStream.range(0, 5).mapToObj(i -> callTool("Bearer jeton-mcp")).toList();

        assertThat(codes.subList(0, 3)).doesNotContain(429);
        assertThat(codes.subList(3, 5)).containsOnly(429);
    }

    private int callTool(String authorization) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "http://localhost:" + port + "/api/agent/mcp/servers/kafka-explorer/tools/kex_list_topics"))
                    .header("Authorization", authorization)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            try (HttpClient client = HttpClient.newHttpClient()) {
                return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            }
        }
        catch (IOException | InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private int chat(String authorization) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/agent/chat"))
                    .header("Authorization", authorization)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"ping\"}"))
                    .build();
            try (HttpClient client = HttpClient.newHttpClient()) {
                return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            }
        }
        catch (IOException | InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private int get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer secret")
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }
}
