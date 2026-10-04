// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.memory.MemoryTools;
import com.kex.agent.tools.ToolControlProperties;
import com.kex.agent.tools.ToolInvocationPolicy;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolRegistryTest {

    @Test
    @SuppressWarnings("unchecked")
    void redecouvre_les_providers_et_applique_les_regles_avant_delegation() {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(DefaultToolDefinition.builder()
                .name("read_lag").description("read").inputSchema("{}").build());
        ObjectProvider<ToolCallbackProvider> providers = mock(ObjectProvider.class);
        when(providers.orderedStream()).thenAnswer(ignored -> Stream.of(ToolCallbackProvider.from(delegate)));
        ObjectProvider<MemoryTools> memory = mock(ObjectProvider.class);
        when(memory.stream()).thenAnswer(ignored -> Stream.empty());
        AgentToolRegistry registry = new AgentToolRegistry(providers, memory, CircuitBreakerRegistry.ofDefaults(),
                policy(Map.of("read_lag", new ToolControlProperties.Rule(false, true,
                        Map.of("/topic", Set.of("orders")), Map.of()))));
        assertThat(registry.callbacks()).hasSize(1);
        assertThatThrownBy(() -> registry.callbacks()[0].call("{\"topic\":\"private\"}"))
                .isInstanceOf(SecurityException.class);
        verify(delegate, never()).call("{\"topic\":\"private\"}");
        verify(providers, org.mockito.Mockito.times(2)).orderedStream();
    }

    @Test
    @SuppressWarnings("unchecked")
    void les_regles_de_refus_couvrent_aussi_les_outils_locaux_de_memoire() {
        ObjectProvider<ToolCallbackProvider> providers = mock(ObjectProvider.class);
        when(providers.orderedStream()).thenAnswer(ignored -> Stream.empty());
        ObjectProvider<MemoryTools> memory = mock(ObjectProvider.class);
        MemoryTools memoryTools = mock(MemoryTools.class);
        when(memory.stream()).thenAnswer(ignored -> Stream.of(memoryTools));
        AgentToolRegistry registry = new AgentToolRegistry(providers, memory, CircuitBreakerRegistry.ofDefaults(),
                policy(Map.of("remember_fact", new ToolControlProperties.Rule(true, false, Map.of(), Map.of()))));
        assertThat(registry.callbacks()).extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("recall_facts");
    }

    private ToolInvocationPolicy policy(Map<String, ToolControlProperties.Rule> rules) {
        return new ToolInvocationPolicy(new ToolControlProperties(false, 12, 256, 1000, rules, List.of()), new ObjectMapper());
    }
}
