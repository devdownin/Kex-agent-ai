// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmRoutingPropertiesTest {

    @Test
    void applies_default_base_urls_and_validates_endpoints() {
        LlmRoutingProperties.Endpoint ollama = new LlmRoutingProperties.Endpoint(
                LlmRoutingProperties.Provider.OLLAMA, null, null, "llama3", null, null, null);
        assertThat(ollama.baseUrl()).isEqualTo("http://localhost:11434/v1");
        assertThat(ollama.apiKey()).isEqualTo("kex-local");

        LlmRoutingProperties.Endpoint vllm = new LlmRoutingProperties.Endpoint(
                LlmRoutingProperties.Provider.VLLM, null, null, "vllm-model", null, null, null);
        assertThat(vllm.baseUrl()).isEqualTo("http://localhost:8000/v1");

        LlmRoutingProperties.Endpoint openai = new LlmRoutingProperties.Endpoint(
                LlmRoutingProperties.Provider.OPENAI, null, "key1", "gpt-4o", null, null, null);
        assertThat(openai.baseUrl()).isEqualTo("https://api.openai.com/v1");

        LlmRoutingProperties.Endpoint anthropic = new LlmRoutingProperties.Endpoint(
                LlmRoutingProperties.Provider.ANTHROPIC, null, "key2", "claude-3-5-sonnet", null, null, null);
        assertThat(anthropic.baseUrl()).isEqualTo("https://api.anthropic.com");

        assertThat(ollama.toString()).contains("REDACTED");

        // Invalid parameters
        assertThatThrownBy(() -> new LlmRoutingProperties.Endpoint(null, null, "key", "model", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new LlmRoutingProperties.Endpoint(LlmRoutingProperties.Provider.OPENAI, "https://api.openai.com", null, "model", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new LlmRoutingProperties.Endpoint(LlmRoutingProperties.Provider.OPENAI, "https://api.openai.com", "key", "model", Duration.ofSeconds(-1), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
