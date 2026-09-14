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

        List<Integer> codes = IntStream.range(0, 5).mapToObj(i -> chat()).toList();

        assertThat(codes).startsWith(200, 200, 200).endsWith(429, 429);
    }

    @Test
    void ne_limite_pas_l_introspection_mcp() throws Exception {
        // Le quota du chat ne doit pas couper la supervision, qui ne coûte pas de jetons.
        for (int i = 0; i < 5; i++) {
            chat();
        }
        assertThat(get("/api/agent/mcp/servers")).isEqualTo(200);
    }

    private int chat() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/agent/chat"))
                    .header("Authorization", "Bearer secret")
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
