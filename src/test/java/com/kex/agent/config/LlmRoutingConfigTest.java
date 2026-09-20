// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmRoutingConfigTest {

    @Test
    void instantiates_routing_chat_model_from_config() {
        LlmRoutingProperties.Endpoint openaiEp = new LlmRoutingProperties.Endpoint(
                LlmRoutingProperties.Provider.OPENAI, "https://api.openai.com/v1", "key1", "gpt-4o", Duration.ofSeconds(30), 4096, 0.2);
        LlmRoutingProperties.Endpoint anthropicEp = new LlmRoutingProperties.Endpoint(
                LlmRoutingProperties.Provider.ANTHROPIC, "https://api.anthropic.com", "key2", "claude-3-5-sonnet", Duration.ofSeconds(30), 4096, 0.2);

        LlmRoutingProperties properties = new LlmRoutingProperties(true, false,
                Map.of("m1", openaiEp, "m2", anthropicEp), Map.of(LlmRoutingProperties.Task.CHAT, List.of("m1", "m2")));

        LlmRoutingConfig config = new LlmRoutingConfig();
        RoutingChatModel router = config.routingChatModel(properties, ObservationRegistry.NOOP);

        assertThat(router).isNotNull();
    }
}
