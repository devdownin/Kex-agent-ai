// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.kex.agent.config.AgentProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

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

    @Mock
    ChatClient.StreamResponseSpec streamSpec;

    private static AgentProperties properties(Duration timeout) {
        return new AgentProperties("prompt", 40, false, "", Map.of(), timeout);
    }

    private AgentService agentService() {
        given(chatClient.prompt()).willReturn(requestSpec);
        given(requestSpec.user(anyString())).willReturn(requestSpec);
        given(requestSpec.advisors(any(Consumer.class))).willReturn(requestSpec);
        given(requestSpec.toolContext(any())).willReturn(requestSpec);
        given(requestSpec.call()).willReturn(callSpec);
        given(callSpec.content()).willReturn("pong");
        return new AgentService(chatClient, chatMemory, properties(Duration.ofSeconds(10)), CircuitBreakerRegistry.ofDefaults());
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
        new AgentService(chatClient, chatMemory, properties(Duration.ofSeconds(10)), CircuitBreakerRegistry.ofDefaults()).clear("conv-1");

        verify(chatMemory).clear("conv-1");
    }

    @Test
    void rend_l_identifiant_avec_le_flux() {
        given(chatClient.prompt()).willReturn(requestSpec);
        given(requestSpec.user(anyString())).willReturn(requestSpec);
        given(requestSpec.advisors(any(Consumer.class))).willReturn(requestSpec);
        given(requestSpec.toolContext(any())).willReturn(requestSpec);
        given(requestSpec.stream()).willReturn(streamSpec);
        given(streamSpec.content()).willReturn(Flux.just("pong"));

        var stream = new AgentService(chatClient, chatMemory, properties(Duration.ofSeconds(10)), CircuitBreakerRegistry.ofDefaults())
                .stream(null, "ping");

        // Sans identifiant rendu, la conversation créée serait inatteignable et impurgeable.
        assertThat(stream.conversationId()).isNotBlank();
        StepVerifier.create(stream.events())
                .expectNext(new AgentEvent.Token("pong"))
                .verifyComplete();
    }

    @Test
    void coupe_un_flux_qui_depasse_le_plafond() {
        given(chatClient.prompt()).willReturn(requestSpec);
        given(requestSpec.user(anyString())).willReturn(requestSpec);
        given(requestSpec.advisors(any(Consumer.class))).willReturn(requestSpec);
        given(requestSpec.toolContext(any())).willReturn(requestSpec);
        given(requestSpec.stream()).willReturn(streamSpec);
        given(streamSpec.content()).willReturn(Flux.never());

        var stream = new AgentService(chatClient, chatMemory, properties(Duration.ofMillis(100)), CircuitBreakerRegistry.ofDefaults())
                .stream("conv-1", "ping");

        StepVerifier.create(stream.events()).expectError(AgentTimeoutException.class).verify();
    }

    @Test
    void borne_l_attente_d_un_appel_bloquant() {
        given(chatClient.prompt()).willReturn(requestSpec);
        given(requestSpec.user(anyString())).willReturn(requestSpec);
        given(requestSpec.advisors(any(Consumer.class))).willReturn(requestSpec);
        given(requestSpec.toolContext(any())).willReturn(requestSpec);
        given(requestSpec.call()).willReturn(callSpec);
        given(callSpec.content()).willAnswer(invocation -> {
            Thread.sleep(5_000);
            return "trop tard";
        });

        AgentService service = new AgentService(chatClient, chatMemory, properties(Duration.ofMillis(100)), CircuitBreakerRegistry.ofDefaults());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.ask("conv-1", "ping"))
                .isInstanceOf(AgentTimeoutException.class);
    }

    @Test
    void rend_une_sortie_structuree() {
        given(chatClient.prompt()).willReturn(requestSpec);
        given(requestSpec.user(anyString())).willReturn(requestSpec);
        given(requestSpec.toolContext(any())).willReturn(requestSpec);
        given(requestSpec.advisors(any(Consumer.class))).willReturn(requestSpec);
        given(requestSpec.call()).willReturn(callSpec);
        given(callSpec.entity(any(org.springframework.ai.converter.StructuredOutputConverter.class)))
                .willReturn(Map.of("total", 8));

        var answer = new AgentService(chatClient, chatMemory, properties(Duration.ofSeconds(10)), CircuitBreakerRegistry.ofDefaults())
                .askStructured("conv-1", "combien ?", Map.of("type", "object"));

        assertThat(answer.conversationId()).isEqualTo("conv-1");
        assertThat(answer.content()).containsEntry("total", 8);
    }

    @Test
    void refuse_une_sortie_structuree_sans_schema() {
        AgentService service = new AgentService(chatClient, chatMemory, properties(Duration.ofSeconds(10)), CircuitBreakerRegistry.ofDefaults());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.askStructured("c", "m", Map.of()))
                .isInstanceOf(InvalidJsonSchemaException.class);
    }

    @Test
    void echoue_vite_apres_plusieurs_pannes_du_fournisseur() {
        given(chatClient.prompt()).willReturn(requestSpec);
        given(requestSpec.user(anyString())).willReturn(requestSpec);
        given(requestSpec.advisors(any(Consumer.class))).willReturn(requestSpec);
        given(requestSpec.toolContext(any())).willReturn(requestSpec);
        given(requestSpec.call()).willReturn(callSpec);
        given(callSpec.content()).willThrow(new AgentTimeoutException(Duration.ofSeconds(1)));

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        registry.circuitBreaker("agent-model", CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .recordExceptions(AgentTimeoutException.class)
                .build());
        AgentService service = new AgentService(chatClient, chatMemory, properties(Duration.ofSeconds(10)), registry);

        // Les deux premiers appels échouent normalement et ouvrent le disjoncteur ; le troisième
        // n'atteint même plus le mock, faute de quoi il attendrait le plafond de temps pour rien.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.ask("c", "m"))
                .isInstanceOf(AgentTimeoutException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.ask("c", "m"))
                .isInstanceOf(AgentTimeoutException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.ask("c", "m"))
                .isInstanceOf(CallNotPermittedException.class);
    }
}
