// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La console est servie sans jeton — sinon le navigateur ne peut pas la charger pour en demander
 * un — mais son ouverture ne doit déborder sur aucune route qui agit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"kex.agent.api-key=secret", "kex.agent.rate-limit.enabled=false"})
@ActiveProfiles("test")
class ConsoleTest {

    @LocalServerPort
    int port;

    @Test
    void sert_la_console_sans_jeton() throws Exception {
        HttpResponse<String> response = get("/");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Kex Agent").contains("Control Center")
                .contains("assets/console.js");
    }

    @Test
    void sert_les_ressources_de_la_console_sans_jeton() throws Exception {
        // Les modules sont importés les uns par les autres : un seul 404 casse toute la console.
        for (String asset : List.of("console.js", "core.js", "supervision.js", "tools.js", "chat.js",
                "console.css", "favicon.svg")) {
            assertThat(get("/assets/" + asset).statusCode()).as(asset).isEqualTo(200);
        }
    }

    @Test
    void la_console_n_ouvre_pas_l_api() throws Exception {
        // Le joker /assets/** ne doit pas se propager : l'API reste fermée et le POST aussi.
        assertThat(get("/api/agent/mcp/servers").statusCode()).isEqualTo(401);
        assertThat(get("/actuator/prometheus").statusCode()).isEqualTo(401);

        HttpRequest post = HttpRequest.newBuilder(URI.create(base() + "/assets/console.js"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            // permitAll est restreint au GET : un POST sur les mêmes chemins reste authentifié.
            assertThat(client.send(post, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
        }
    }

    @Test
    void la_console_appelle_les_routes_qu_elle_declare() throws Exception {
        // Attrape une divergence entre les chemins codés dans la console et les contrôleurs, que
        // ni le compilateur ni le test de la spécification ne voient.
        String modules = get("/assets/console.js").body() + get("/assets/chat.js").body()
                + get("/assets/tools.js").body() + get("/assets/supervision.js").body();
        assertThat(modules)
                .contains("/api/agent/chat/stream")
                .contains("/api/agent/conversations/")
                .contains("/api/agent/mcp/servers")
                .contains("/api/agent/supervision");

        // Les vues de supervision et les routes du contrôleur doivent citer les mêmes segments.
        for (String route : List.of("/overview", "/status", "/cycles", "/decisions", "/policy",
                "/audit", "/processes", "/alerts", "/performance")) {
            assertThat(modules).as(route).contains(route);
        }
        // La mise en pause et la reprise partagent un gabarit : c'est le segment qui est cité.
        assertThat(modules).contains("'resume'").contains("'pause'");
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + path)).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
