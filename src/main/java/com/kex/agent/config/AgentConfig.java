package com.kex.agent.config;

import com.kex.agent.mcp.McpToolCatalog;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration(proxyBeanMethods = false)
class AgentConfig {

    @Bean
    McpSyncHttpClientRequestCustomizer mcpBearerTokenCustomizer(McpAuthProperties properties) {
        return new McpBearerTokenCustomizer(properties.bearerTokens());
    }

    /** ObjectProvider : le contexte doit démarrer même sans serveur MCP configuré. */
    @Bean
    McpToolCatalog mcpToolCatalog(ObjectProvider<List<McpSyncClient>> mcpSyncClients) {
        return new McpToolCatalog(mcpSyncClients.getIfAvailable(List::of));
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
                .defaultToolCallbacks(toolCallbackProviders.stream().toArray(ToolCallbackProvider[]::new))
                .defaultAdvisors(advisors)
                .build();
    }
}
