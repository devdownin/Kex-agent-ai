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

/** Sans jeton configuré, l'API est fermée : 503, pas ouverte par défaut d'installation. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "kex.agent.api-key=")
@ActiveProfiles("test")
class ApiSecurityUnconfiguredTest {

    @LocalServerPort
    int port;

    @Test
    void refuse_tout_appel_api() throws Exception {
        assertThat(status("/api/agent/mcp/servers")).isEqualTo(503);
    }

    @Test
    void laisse_passer_la_sonde_de_sante() throws Exception {
        assertThat(status("/actuator/health")).isEqualTo(200);
    }

    private int status(String path) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }
}
