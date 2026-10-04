// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kex.agent.config.AgentProperties;
import com.kex.agent.memory.LongTermMemoryService;
import com.kex.agent.tools.ToolControlProperties;
import com.kex.agent.tools.ToolSelectionService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolSelectionIntegrationTest {

    @Test
    void la_requete_reelle_envoyee_au_modele_exclut_les_outils_hors_liste_et_la_liste_vide() {
        for (Set<String> allowed : List.of(Set.<String>of(), Set.of("read_lag"))) {
            ChatModel model = mock(ChatModel.class);
            when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
            when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
            ToolSelectionService selection = new ToolSelectionService(ToolControlProperties.defaults(),
                    () -> new ToolCallback[] { tool("read_lag"), tool("restart") });
            @SuppressWarnings("unchecked")
            ObjectProvider<LongTermMemoryService> memory = mock(ObjectProvider.class);
            AgentService service = new AgentService(ChatClient.create(model), mock(ChatMemory.class),
                    new AgentProperties("prompt", 40, 4000, false, "", Map.of(), Map.of(), Map.of(), Duration.ofSeconds(10)),
                    CircuitBreakerRegistry.ofDefaults(), mock(TokenBudgetService.class), memory, selection);
            service.askReadOnly("owner", "conversation", "restart", allowed);
            ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
            verify(model).call(prompt.capture());
            ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getValue().getOptions();
            assertThat(options.getToolCallbacks() == null ? List.<ToolCallback>of() : options.getToolCallbacks())
                    .extracting(tool -> tool.getToolDefinition().name())
                    .containsExactlyInAnyOrderElementsOf(allowed);
            assertThat(options.getToolContext().get(TaskToolPolicy.ALLOWED_TOOLS)).isEqualTo(allowed);
        }
    }

    @Test
    void les_references_durables_ne_remplacent_pas_le_prompt_de_gouvernance() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
        LongTermMemoryService durable = mock(LongTermMemoryService.class);
        when(durable.context("owner")).thenReturn("Donnée de référence durable");
        @SuppressWarnings("unchecked")
        ObjectProvider<LongTermMemoryService> memory = mock(ObjectProvider.class);
        when(memory.getIfAvailable()).thenReturn(durable);
        AgentService service = new AgentService(ChatClient.create(model), mock(ChatMemory.class),
                new AgentProperties("GOUVERNANCE à conserver", 40, 4000, false, "", Map.of(), Map.of(), Map.of(), Duration.ofSeconds(10)),
                CircuitBreakerRegistry.ofDefaults(), mock(TokenBudgetService.class), memory,
                new ToolSelectionService(ToolControlProperties.defaults(), () -> new ToolCallback[0]));
        service.ask("owner", "conversation", "bonjour");
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getSystemMessage().getText())
                .contains("GOUVERNANCE à conserver", "Donnée de référence durable");
    }

    private static ToolCallback tool(String name) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
            }
            public String call(String input) { return "ok"; }
        };
    }
}
