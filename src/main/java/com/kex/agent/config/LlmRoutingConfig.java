// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.util.LinkedHashMap;
import java.util.Map;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "kex.models", name = "enabled", havingValue = "true")
class LlmRoutingConfig {

    @Bean
    @Primary
    RoutingChatModel routingChatModel(LlmRoutingProperties properties, ObservationRegistry observations) {
        Map<String, ChatModel> models = new LinkedHashMap<>();
        properties.endpoints().forEach((name, endpoint) -> {
            // Disable SDK retries: each explicitly configured destination gets one bounded attempt.
            ChatModel model = endpoint.provider() == LlmRoutingProperties.Provider.ANTHROPIC
                    ? AnthropicChatModel.builder().options(AnthropicChatOptions.builder()
                            .baseUrl(endpoint.baseUrl()).apiKey(endpoint.apiKey()).model(endpoint.model())
                            .timeout(endpoint.timeout()).maxRetries(0).maxTokens(endpoint.maxTokens())
                            .temperature(endpoint.temperature()).build()).observationRegistry(observations).build()
                    : OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
                            .baseUrl(endpoint.baseUrl()).apiKey(endpoint.apiKey()).model(endpoint.model())
                            .timeout(endpoint.timeout()).maxRetries(0).maxTokens(endpoint.maxTokens())
                            .temperature(endpoint.temperature()).build()).observationRegistry(observations).build();
            models.put(name, model);
        });
        return new RoutingChatModel(properties, models);
    }
}
