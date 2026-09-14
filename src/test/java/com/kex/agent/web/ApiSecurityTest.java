package com.kex.agent.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "kex.agent.api-key=secret")
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
}
