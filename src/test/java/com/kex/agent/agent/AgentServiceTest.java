package com.kex.agent.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentServiceTest {

    @Mock
    ChatClient chatClient;

    @Mock
    ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    ChatClient.CallResponseSpec callSpec;

    @Mock
    ChatMemory chatMemory;

    @Mock
    ChatClient.AdvisorSpec advisorSpec;

    private AgentService agentService() {
        given(chatClient.prompt()).willReturn(requestSpec);
        given(requestSpec.user(anyString())).willReturn(requestSpec);
        given(requestSpec.advisors(any(Consumer.class))).willReturn(requestSpec);
        given(requestSpec.call()).willReturn(callSpec);
        given(callSpec.content()).willReturn("pong");
        return new AgentService(chatClient, chatMemory);
    }

    @Test
    void reutilise_l_identifiant_de_conversation_fourni() {
        AgentAnswer answer = agentService().ask("conv-1", "ping");

        assertThat(answer.conversationId()).isEqualTo("conv-1");
        assertThat(answer.content()).isEqualTo("pong");
    }

    @Test
    void genere_un_identifiant_quand_il_est_absent() {
        AgentAnswer answer = agentService().ask("  ", "ping");

        assertThat(answer.conversationId()).isNotBlank().isNotEqualTo("  ");
    }

    @Test
    void propage_l_identifiant_de_conversation_a_l_advisor_de_memoire() {
        Map<String, Object> params = new HashMap<>();
        given(advisorSpec.param(anyString(), any())).willAnswer(invocation -> {
            params.put(invocation.getArgument(0), invocation.getArgument(1));
            return advisorSpec;
        });

        agentService().ask("conv-42", "ping");

        ArgumentCaptor<Consumer<ChatClient.AdvisorSpec>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(requestSpec).advisors(captor.capture());
        captor.getValue().accept(advisorSpec);

        assertThat(params).containsEntry(ChatMemory.CONVERSATION_ID, "conv-42");
    }

    @Test
    void purge_la_memoire_de_la_conversation() {
        new AgentService(chatClient, chatMemory).clear("conv-1");

        verify(chatMemory).clear("conv-1");
    }
}
