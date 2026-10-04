// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.tools.ToolControlProperties;
import com.kex.agent.tools.ToolInvocationPolicy;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolPolicyTest {

    @Test
    void une_invocation_directe_ne_contourne_pas_la_restriction_de_ressources() {
        McpSyncClient client = client();
        var rule = new ToolControlProperties.Rule(false, false, Map.of("/topic", Set.of("orders")), Map.of());
        McpToolCatalog catalog = catalog(client, Map.of("export", rule));
        assertThatThrownBy(() -> catalog.call("test", "export", Map.of("topic", "secrets")))
                .isInstanceOf(SecurityException.class);
        verify(client, never()).callTool(any(McpSchema.CallToolRequest.class));
        assertThat(catalog.isReadOnly("test", "export")).isFalse();
    }

    @Test
    void un_outil_non_classe_en_lecture_seule_n_est_jamais_rejoue_apres_une_erreur_ambigue() {
        McpSyncClient client = client();
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenThrow(new IllegalStateException("timeout"));
        McpToolCatalog catalog = catalog(client, Map.of());
        assertThatThrownBy(() -> catalog.call("test", "restart", Map.of())).isInstanceOf(RuntimeException.class);
        verify(client, times(1)).callTool(any(McpSchema.CallToolRequest.class));
    }

    @Test
    void un_resultat_sensible_est_refuse_aussi_pour_le_chemin_direct() {
        McpSyncClient client = client();
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("token=SECRET-42")), false, null, null));
        assertThatThrownBy(() -> catalog(client, Map.of()).call("test", "read", Map.of()))
                .isInstanceOf(SecurityException.class).hasMessageNotContaining("SECRET-42");
    }

    private McpSyncClient client() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getClientInfo()).thenReturn(new McpSchema.Implementation("kex-agent - test", "1"));
        when(client.isInitialized()).thenReturn(true);
        return client;
    }

    private McpToolCatalog catalog(McpSyncClient client, Map<String, ToolControlProperties.Rule> rules) {
        RetryRegistry retries = RetryRegistry.ofDefaults();
        retries.retry("mcp-tool", RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ofMillis(1)).build());
        var mapper = new ObjectMapper();
        var policy = new ToolInvocationPolicy(new ToolControlProperties(false, 2, 256, 1000, rules,
                List.of("SECRET-[0-9]+")), mapper);
        return new McpToolCatalog(List.of(client), ObservationRegistry.NOOP, CircuitBreakerRegistry.ofDefaults(),
                retries, new SimpleMeterRegistry(), mapper,
                new McpRuntimeProperties(".kex/mcp-servers.enc", "", Duration.ofSeconds(30), 50, List.of()), policy);
    }
}
