// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.web;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"kex.agent.api-key=secret", "kex.agent.rate-limit.enabled=false"})
@ActiveProfiles("test")
class ApiSecurityTest {

    @LocalServerPort
    int port;

    @Test
    void refuse_un_appel_sans_jeton() throws Exception {
        assertThat(status("/api/agent/mcp/servers", null)).isEqualTo(401);
    }

    @Test
    void refuse_un_mauvais_jeton() throws Exception {
        assertThat(status("/api/agent/mcp/servers", "Bearer faux")).isEqualTo(401);
    }

    @Test
    void accepte_le_jeton_configure() throws Exception {
        assertThat(status("/api/agent/mcp/servers", "Bearer secret")).isEqualTo(200);
    }

    @Test
    void accepte_un_post_authentifie_sans_jeton_csrf() throws Exception {
        // La protection CSRF n'est levée que sur /api/** : un POST porteur du bearer doit aboutir
        // à la logique métier (404 : connexion inconnue), pas être refusé en 403 par le filtre CSRF.
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/agent/mcp/servers/inconnu/tools/x"))
                .header("Authorization", "Bearer secret")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
        }
    }

    @Test
    void la_configuration_du_modele_reste_fermee() throws Exception {
        // Elle rend le point d'accès, le prompt système et la présence d'une clé : la forme de
        // l'API est publique, la configuration de l'instance ne l'est pas.
        assertThat(status("/api/agent/llm", null)).isEqualTo(401);
        assertThat(status("/api/agent/llm", "Bearer secret")).isEqualTo(200);
    }

    @Test
    void laisse_passer_la_sonde_de_sante() throws Exception {
        assertThat(status("/actuator/health", null)).isEqualTo(200);
    }

    private int status(String path, String authorization) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    @Test
    void expose_les_metriques_mais_pas_sans_jeton() throws Exception {
        // /actuator/prometheus porte le modèle, le volume de jetons et les outils appelés :
        // il ne suit pas /health en permitAll.
        assertThat(status("/actuator/prometheus", null)).isEqualTo(401);
        assertThat(status("/actuator/prometheus", "Bearer secret")).isEqualTo(200);
    }

    @Test
    void publie_la_specification_sans_ouvrir_l_api() throws Exception {
        // La forme de l'API est déjà publique ; ce sont les routes qui agissent qui sont fermées.
        assertThat(status("/v3/api-docs", null)).isEqualTo(200);
        assertThat(status("/swagger-ui/index.html", null)).isEqualTo(200);
        assertThat(status("/api/agent/mcp/servers", null)).isEqualTo(401);
    }

    @Test
    void la_specification_decrit_le_schema_bearer() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            String body = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
            assertThat(body).contains("\"bearer\"").contains("/api/agent/chat")
                    .contains("/api/agent/mcp/servers/{connection}/tools/{tool}");
        }
    }
}
