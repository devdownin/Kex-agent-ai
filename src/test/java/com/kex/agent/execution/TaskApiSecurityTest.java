// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.execution;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "kex.agent.api-key=admin-secret", "kex.agent.rate-limit.enabled=false", "kex.agent.tasks.enabled=true",
        "kex.agent.api-keys.operator=ops-secret", "kex.agent.api-key-roles.operator=OPERATOR",
        "kex.agent.api-key-tenants.operator=kex-agent-api", "kex.agent.api-keys.chat=chat-secret",
        "kex.agent.api-keys.other=other-secret", "kex.agent.api-key-roles.other=OPERATOR",
        "kex.agent.tasks.bindings.read.connection=mcp", "kex.agent.tasks.bindings.read.tool=read",
        "kex.agent.tasks.bindings.read.read-only=true", "kex.agent.tasks.bindings.restart.connection=mcp",
        "kex.agent.tasks.bindings.restart.tool=restart", "kex.agent.tasks.bindings.restart.capability=RESTART_CONSUMER",
        "kex.agent.supervision.autonomy.RESTART_CONSUMER=SUPERVISED" })
@ActiveProfiles("test")
class TaskApiSecurityTest {
    @LocalServerPort int port;
    @MockitoBean TaskGateway gateway;
    private final ObjectMapper json = new ObjectMapper();
    @DynamicPropertySource static void storage(DynamicPropertyRegistry registry) throws Exception {
        var directory = Files.createTempDirectory("kex-task-api-test");
        registry.add("kex.agent.tasks.store-path", directory::toString);
    }
    @BeforeEach void schema() { when(gateway.schema(anyString(), anyString())).thenReturn(Map.of("type", "object")); }
    private HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/agent/tasks" + path))
                .header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try (var client = HttpClient.newHttpClient()) { return client.send(builder.build(), HttpResponse.BodyHandlers.ofString()); }
    }
    private String plan(String binding) {
        return """
                {"objective":"Contrôler commandes","preconditions":[],"steps":[
                  {"id":"one","description":"Contrôle","binding":"%s","arguments":{},"dependsOn":[],"expectation":null}]}
                """.formatted(binding);
    }
    @Test void unauthenticated_and_chat_only_cannot_access_task_surface() throws Exception {
        for (String path : new String[]{"", "/bindings", "/unknown"}) {
            assertThat(request("GET", path, null, null).statusCode()).isEqualTo(401);
            assertThat(request("GET", path, "chat-secret", null).statusCode()).isEqualTo(403);
        }
        for (String path : new String[]{"", "/plan", "/unknown/approve", "/unknown/run", "/unknown/cancel"}) {
            assertThat(request("POST", path, "chat-secret", "{}").statusCode()).isEqualTo(403);
        }
    }
    @Test void operator_approves_read_only_but_mutation_requires_admin() throws Exception {
        var read = request("POST", "", "ops-secret", plan("read"));
        assertThat(read.statusCode()).isEqualTo(201);
        String readId = json.readTree(read.body()).path("id").asText();
        assertThat(request("POST", "/" + readId + "/approve", "ops-secret", null).statusCode()).isEqualTo(200);
        var mutation = request("POST", "", "ops-secret", plan("restart"));
        assertThat(mutation.statusCode()).isEqualTo(201);
        String mutationId = json.readTree(mutation.body()).path("id").asText();
        assertThat(request("POST", "/" + mutationId + "/approve", "ops-secret", null).statusCode()).isEqualTo(409);
        assertThat(request("POST", "/" + mutationId + "/approve", "admin-secret", null).statusCode()).isEqualTo(200);
    }
    @Test void owner_is_derived_from_token_and_other_tenant_cannot_read_or_change_task() throws Exception {
        var response = request("POST", "", "ops-secret", plan("read"));
        var task = json.readTree(response.body());
        assertThat(task.path("owner").asText()).isEqualTo("kex-agent-api");
        String id = task.path("id").asText();
        assertThat(request("GET", "/" + id, "other-secret", null).statusCode()).isEqualTo(409);
        for (String verb : new String[]{"approve", "run", "cancel"}) {
            assertThat(request("POST", "/" + id + "/" + verb, "other-secret", null).statusCode()).isEqualTo(409);
        }
        assertThat(json.readTree(request("GET", "", "other-secret", null).body())).isEmpty();
    }
}
