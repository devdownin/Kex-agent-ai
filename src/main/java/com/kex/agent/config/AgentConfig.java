// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.util.ArrayList;
import java.util.List;

import com.kex.agent.mcp.McpToolCatalog;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AgentConfig {

    @Bean
    McpSyncHttpClientRequestCustomizer mcpBearerTokenCustomizer(McpAuthProperties properties) {
        return new McpBearerTokenCustomizer(properties.bearerTokens());
    }

    /** ObjectProvider : le contexte doit démarrer même sans serveur MCP configuré. */
    @Bean
    McpToolCatalog mcpToolCatalog(ObjectProvider<List<McpSyncClient>> mcpSyncClients,
                                  ObservationRegistry observationRegistry) {
        return new McpToolCatalog(mcpSyncClients.getIfAvailable(List::of), observationRegistry);
    }

    /**
     * Le convertisseur par défaut recopie tout le {@code ToolContext} dans le {@code _meta} envoyé
     * au serveur MCP : nos clés internes — dont le collecteur d'événements, non sérialisable —
     * partiraient sur le réseau. Elles sont filtrées, le reste passe pour les serveurs qui s'en
     * servent.
     */
    @Bean
    ToolContextToMcpMetaConverter toolContextToMcpMetaConverter() {
        return context -> context.getContext().entrySet().stream()
                .filter(entry -> !entry.getKey().startsWith("kex.") && !"exchange".equals(entry.getKey()))
                .collect(java.util.stream.Collectors.toMap(java.util.Map.Entry::getKey,
                        java.util.Map.Entry::getValue));
    }

    @Bean
    ChatMemory chatMemory(ChatMemoryRepository repository, AgentProperties properties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(properties.maxHistoryMessages())
                .build();
    }

    /**
     * Les providers MCP sont interrogés à chaque requête (getToolCallbacks), donc un serveur
     * qui publie de nouveaux outils est pris en compte sans redémarrage.
     */
    @Bean
    ChatClient agentChatClient(ChatClient.Builder builder,
                               ChatMemory chatMemory,
                               ObjectProvider<ToolCallbackProvider> toolCallbackProviders,
                               AgentProperties properties) {

        List<Advisor> advisors = new ArrayList<>();
        advisors.add(MessageChatMemoryAdvisor.builder(chatMemory).build());
        if (properties.logInteractions()) {
            advisors.add(new SimpleLoggerAdvisor());
        }

        return builder
                .defaultSystem(properties.systemPrompt())
                .defaultToolCallbacks(toolCallbackProviders.stream()
                        .map(RecordingToolCallbackProvider::new)
                        .toArray(ToolCallbackProvider[]::new))
                .defaultAdvisors(advisors)
                .build();
    }
}
