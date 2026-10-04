// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.workspace;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;

import com.kex.agent.agent.AgentEvent;
import com.kex.agent.agent.AgentService;
import com.kex.agent.agent.AgentStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "kex.agent.api-key=admin-secret", "kex.agent.rate-limit.enabled=false",
        "kex.agent.api-keys.alice=alice-secret", "kex.agent.api-key-roles.alice=CHAT", "kex.agent.api-key-tenants.alice=team",
        "kex.agent.api-keys.bob=bob-secret", "kex.agent.api-key-roles.bob=CHAT", "kex.agent.api-key-tenants.bob=team" })
@ActiveProfiles("test")
class WorkspaceApiTest {
    @LocalServerPort int port;
    @MockitoBean AgentService agent;
    @DynamicPropertySource static void storage(DynamicPropertyRegistry registry) throws Exception {
        var path = Files.createTempDirectory("kex-workspace-api"); registry.add("kex.agent.workspace.storage-directory", path::toString);
    }
    HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/agent/workspace/requests" + path)).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try (var client = HttpClient.newHttpClient()) { return client.send(builder.build(), HttpResponse.BodyHandlers.ofString()); }
    }
    @Test void le_flux_et_l_historique_sont_prives_au_compte_et_la_validation_precede_le_modele() throws Exception {
        when(agent.stream(anyString(), anyString(), anyString())).thenAnswer(call -> new AgentStream(call.getArgument(1), Flux.just(new AgentEvent.Token("Réponse"))));
        assertThat(request("GET", "", null, null).statusCode()).isEqualTo(401);
        assertThat(request("POST", "/stream", "alice-secret", "{\"message\":\"\"}").statusCode()).isEqualTo(400);
        assertThat(request("POST", "/stream", "alice-secret", "{\"message\":\"Bonjour\",\"context\":{\"files\":[{\"name\":\"x.txt\",\"text\":\"\"}]}}").statusCode()).isEqualTo(400);
        var response = request("POST", "/stream", "alice-secret", "{\"message\":\"Bonjour\"}"); assertThat(response.statusCode()).isEqualTo(200); assertThat(response.body()).contains("event:request", "event:done");
        var json = new com.fasterxml.jackson.databind.ObjectMapper(); var rows = json.readTree(request("GET", "", "alice-secret", null).body());
        var id = rows.get(0).path("id").asText(); assertThat(rows.get(0).path("status").asText()).isEqualTo("COMPLETE");
        assertThat(request("GET", "/" + id, "alice-secret", null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/" + id, "bob-secret", null).statusCode()).isEqualTo(404);
        assertThat(request("GET", "", "bob-secret", null).body()).isEqualTo("[]");
        assertThat(request("POST", "/" + id + "/plan", "alice-secret", "{\"taskId\":\"task\"}").statusCode()).isEqualTo(403);
    }
}
