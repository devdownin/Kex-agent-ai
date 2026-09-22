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

/**
 * Ce que la console affiche désormais dans le menu du jeton : à qui appartiennent les compétences
 * et la charte qu'elle lit. Une clé nommée sans locataire déclaré reste son propre locataire, une
 * clé de chat sans rôle de gestion peut quand même se lire elle-même.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"kex.agent.api-keys.ops-console=jeton-ops", "kex.agent.api-keys.chat-only=jeton-chat",
                "kex.agent.api-key-roles.ops-console=OPERATOR",
                "kex.agent.api-key-tenants.ops-console=exploitation",
                "kex.agent.rate-limit.enabled=false"})
@ActiveProfiles("test")
class WhoAmIControllerTest {

    @LocalServerPort
    int port;

    @Test
    void rend_l_acteur_et_le_locataire_declare() throws IOException, InterruptedException {
        assertThat(body("Bearer jeton-ops"))
                .contains("\"name\":\"ops-console\"")
                .contains("\"tenant\":\"exploitation\"")
                .contains("\"roles\":[\"OPERATOR\"]");
    }

    /** Sans locataire déclaré, il reste son propre locataire — c'est tout ce qui rend le repli sûr. */
    @Test
    void sans_locataire_declare_le_locataire_est_le_nom() throws IOException, InterruptedException {
        assertThat(body("Bearer jeton-chat"))
                .contains("\"name\":\"chat-only\"")
                .contains("\"tenant\":\"chat-only\"")
                .contains("\"roles\":[\"CHAT\"]");
    }

    @Test
    void refuse_un_jeton_inconnu() throws IOException, InterruptedException {
        assertThat(status("Bearer inconnu")).isEqualTo(401);
    }

    private String body(String authorization) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/agent/whoami"))
                .header("Authorization", authorization)
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        }
    }

    private int status(String authorization) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/agent/whoami"))
                .header("Authorization", authorization)
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }
}
