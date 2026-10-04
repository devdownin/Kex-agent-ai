// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.util.ArrayList;
import java.util.List;

import com.kex.agent.memory.MemoryTools;
import com.kex.agent.tools.ToolInvocationPolicy;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Catalogue réévalué par requête, sans outils par défaut qui contourneraient la sélection. */
@Component
public class AgentToolRegistry {

    private final ObjectProvider<ToolCallbackProvider> providers;
    private final ObjectProvider<MemoryTools> memory;
    private final CircuitBreakerRegistry circuitBreakers;
    private final ToolInvocationPolicy policy;

    public AgentToolRegistry(ObjectProvider<ToolCallbackProvider> providers, ObjectProvider<MemoryTools> memory,
                             CircuitBreakerRegistry circuitBreakers, ToolInvocationPolicy policy) {
        this.providers = providers;
        this.memory = memory;
        this.circuitBreakers = circuitBreakers;
        this.policy = policy;
    }

    public ToolCallback[] callbacks() {
        List<ToolCallback> result = new ArrayList<>();
        var breaker = circuitBreakers.circuitBreaker("mcp-tool");
        providers.orderedStream().forEach(provider -> result.addAll(List.of(
                new RecordingToolCallbackProvider(provider, breaker, policy).getToolCallbacks())));
        // Les outils mémoire disposent déjà de leur collecteur interne, d'où recordCalls=false.
        memory.stream().forEach(tools -> result.addAll(List.of(new RecordingToolCallbackProvider(
                ToolCallbackProvider.from(ToolCallbacks.from(tools)), circuitBreakers.circuitBreaker("local-tool"), policy, false).getToolCallbacks())));
        return result.stream().filter(tool -> !policy.isDenied(tool.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
    }
}
