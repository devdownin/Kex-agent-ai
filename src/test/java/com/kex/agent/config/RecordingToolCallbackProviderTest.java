// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.time.Duration;
import java.util.Map;

import com.kex.agent.agent.AgentEvent;
import com.kex.agent.agent.ToolCallRecorder;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordingToolCallbackProviderTest {

    private static ToolCallback callback(String result, RuntimeException failure) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("echo").description("echo")
                        .inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return call(toolInput, null);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                if (failure != null) {
                    throw failure;
                }
                return result;
            }
        };
    }

    private static ToolCallback wrap(ToolCallback delegate) {
        return wrap(delegate, CircuitBreaker.ofDefaults("mcp-tool"));
    }

    private static ToolCallback wrap(ToolCallback delegate, CircuitBreaker circuitBreaker) {
        return new RecordingToolCallbackProvider(ToolCallbackProvider.from(delegate), circuitBreaker)
                .getToolCallbacks()[0];
    }

    @Test
    void enregistre_l_appel_reussi() {
        ToolCallRecorder recorder = new ToolCallRecorder();

        String result = wrap(callback("pong", null))
                .call("{}", new ToolContext(Map.of(ToolCallRecorder.CONTEXT_KEY, recorder)));

        assertThat(result).isEqualTo(
                "<tool_result tool=\"echo\" trust=\"untrusted\">\npong\n</tool_result>");
        assertThat(recorder.calls()).singleElement().satisfies(call -> {
            assertThat(call.tool()).isEqualTo("echo");
            assertThat(call.failed()).isFalse();
            assertThat(call.durationMillis()).isNotNegative();
        });
    }

    @Test
    void enregistre_l_appel_en_echec() {
        ToolCallRecorder recorder = new ToolCallRecorder();
        ToolCallback wrapped = wrap(callback(null, new IllegalStateException("boum")));
        ToolContext context = new ToolContext(Map.of(ToolCallRecorder.CONTEXT_KEY, recorder));

        assertThatThrownBy(() -> wrapped.call("{}", context)).isInstanceOf(IllegalStateException.class);

        assertThat(recorder.calls()).singleElement()
                .extracting(AgentEvent.ToolCall::failed).isEqualTo(true);
    }

    @Test
    void balise_le_resultat_meme_sans_collecteur() {
        assertThat(wrap(callback("pong", null)).call("{}", new ToolContext(Map.of())))
                .isEqualTo("<tool_result tool=\"echo\" trust=\"untrusted\">\npong\n</tool_result>");
    }

    @Test
    void balise_le_resultat_de_l_appel_a_un_seul_argument() {
        assertThat(wrap(callback("pong", null)).call("{}"))
                .isEqualTo("<tool_result tool=\"echo\" trust=\"untrusted\">\npong\n</tool_result>");
    }

    /**
     * Le chemin piloté par le LLM appelle le même {@code McpSyncClient} que l'invocation directe de
     * {@code McpToolCatalog} : sans ce disjoncteur, un serveur MCP qui dégrade pendant une
     * conversation ferait attendre chaque appel jusqu'au plafond de temps au lieu d'échouer vite.
     */
    @Test
    void ouvre_le_disjoncteur_apres_des_echecs_repetes() {
        CircuitBreaker circuitBreaker = CircuitBreaker.of("mcp-tool", CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build());
        ToolCallback wrapped = wrap(callback(null, new IllegalStateException("boum")), circuitBreaker);

        assertThatThrownBy(() -> wrapped.call("{}")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> wrapped.call("{}")).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> wrapped.call("{}")).isInstanceOf(CallNotPermittedException.class);
    }
}
