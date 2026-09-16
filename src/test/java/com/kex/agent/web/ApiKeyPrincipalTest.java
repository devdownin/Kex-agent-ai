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
 * Avec des clés API nommées, l'acteur inscrit à l'audit est le nom de l'opérateur, pas un jeton
 * unique et anonyme partagé par tout le monde.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"kex.agent.api-keys.ops-console=jeton-ops", "kex.agent.api-keys.ci-pipeline=jeton-ci",
                "kex.agent.rate-limit.enabled=false"})
@ActiveProfiles("test")
class ApiKeyPrincipalTest {

    @LocalServerPort
    int port;

    @Test
    void distingue_deux_operateurs_nommes_dans_l_audit() throws Exception {
        post("/api/agent/supervision/pause", "Bearer jeton-ops");
        post("/api/agent/supervision/resume", "Bearer jeton-ci");

        String audit = get("/api/agent/supervision/audit", "Bearer jeton-ops");

        assertThat(audit).contains("\"actor\":\"ops-console\"").contains("\"actor\":\"ci-pipeline\"");
    }

    @Test
    void refuse_toujours_un_jeton_qui_ne_correspond_a_aucun_nom() throws Exception {
        assertThat(status("/api/agent/mcp/servers", "Bearer inconnu")).isEqualTo(401);
    }

    private void post(String path, String authorization) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            client.send(request, HttpResponse.BodyHandlers.discarding());
        }
    }

    private String get(String path, String authorization) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", authorization)
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        }
    }

    private int status(String path, String authorization) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", authorization)
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }
}
