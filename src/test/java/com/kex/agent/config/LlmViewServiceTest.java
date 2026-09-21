// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.time.Duration;
import java.util.Map;

import com.kex.agent.knowledge.KnowledgeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class LlmViewServiceTest {

    private MockEnvironment environment;
    private AgentProperties agentProperties;
    private KnowledgeProperties knowledgeProperties;
    private LlmViewService service;

    @BeforeEach
    void setUp() {
        environment = new MockEnvironment();
        agentProperties = new AgentProperties("prompt", 40, 4000, true, "secret", Map.of(), Map.of(), Map.of(), Duration.ofSeconds(120));
        knowledgeProperties = new KnowledgeProperties(false, 4, 0.6, "");
        service = new LlmViewService(environment, agentProperties, knowledgeProperties);
    }

    @Test
    void describes_anthropic_and_openai_providers() {
        environment.setProperty("spring.ai.model.chat", "anthropic");
        environment.setProperty("spring.ai.anthropic.base-url", "https://api.anthropic.com");
        environment.setProperty("spring.ai.anthropic.api-key", "sk-ant-test");
        environment.setProperty("spring.ai.anthropic.chat.options.model", "claude-3-5-sonnet");

        LlmView view = service.describe();

        assertThat(view.provider()).isEqualTo("anthropic");
        assertThat(view.model()).isEqualTo("claude-3-5-sonnet");
        assertThat(view.apiKeyPresent()).isTrue();
        assertThat(service.keyKnownMissing()).isFalse();

        // Missing key
        environment.setProperty("spring.ai.anthropic.api-key", "");
        assertThat(service.keyKnownMissing()).isTrue();
    }

    @Test
    void describes_openai_and_openrouter_gateways() {
        environment.setProperty("spring.ai.model.chat", "openai");
        environment.setProperty("spring.ai.openai.base-url", "https://openrouter.ai/api/v1");
        environment.setProperty("spring.ai.openai.api-key", "sk-or-v1-test");
        environment.setProperty("spring.ai.openai.chat.options.model", "openai/gpt-4o");

        LlmView view = service.describe();

        assertThat(view.provider()).isEqualTo("openai");
        assertThat(view.label()).isEqualTo("OpenRouter");
        assertThat(view.gateway()).isTrue();
        assertThat(view.apiKeyVariable()).isEqualTo("OPENROUTER_API_KEY");
    }

    @Test
    void describes_task_routing() {
        environment.setProperty("kex.models.enabled", "true");
        environment.setProperty("kex.models.routes.chat[0]", "m1");
        environment.setProperty("kex.models.endpoints.m1.provider", "OPENAI");
        environment.setProperty("kex.models.endpoints.m1.baseUrl", "https://api.openai.com/v1");
        environment.setProperty("kex.models.endpoints.m1.apiKey", "key1");
        environment.setProperty("kex.models.endpoints.m1.model", "gpt-4o");

        LlmView view = service.describe();

        assertThat(view.provider()).isEqualTo("routing");
        assertThat(view.label()).isEqualTo("Routage par tâche");
        assertThat(view.model()).isEqualTo("gpt-4o");
        assertThat(service.keyKnownMissing()).isFalse();
    }
}
