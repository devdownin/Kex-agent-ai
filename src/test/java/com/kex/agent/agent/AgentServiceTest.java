// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
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
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.converter.StructuredOutputConverter;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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
        return new AgentProperties("prompt", 40, 4000, false, "", Map.of(), timeout);
    }

    private static ChatResponse response(String text) {
        return response(text, "end_turn", null);
    }

    private static ChatResponse response(String text, String finishReason, Usage usage) {
        Generation generation = new Generation(new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(finishReason).build());
        return usage == null
                ? new ChatResponse(List.of(generation))
                : new ChatResponse(List.of(generation), ChatResponseMetadata.builder().usage(usage).build());
    }

    private AgentService service(Duration timeout) {
        return new AgentService(chatClient, chatMemory, properties(timeout), CircuitBreakerRegistry.ofDefaults());
    }

    private void blockingCall() {
        given(chatClient.prompt()).willReturn(requestSpec);
        given(requestSpec.user(anyString())).willReturn(requestSpec);
        given(requestSpec.advisors(any(Consumer.class))).willReturn(requestSpec);
        given(requestSpec.toolContext(any())).willReturn(requestSpec);
        given(requestSpec.call()).willReturn(callSpec);
    }

    private void streamingCall() {
        given(chatClient.prompt()).willReturn(requestSpec);
        given(requestSpec.user(anyString())).willReturn(requestSpec);
        given(requestSpec.advisors(any(Consumer.class))).willReturn(requestSpec);
        given(requestSpec.toolContext(any())).willReturn(requestSpec);
        given(requestSpec.stream()).willReturn(streamSpec);
    }

    private AgentService agentService() {
        blockingCall();
        given(callSpec.chatResponse()).willReturn(response("pong"));
        return service(Duration.ofSeconds(10));
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
        service(Duration.ofSeconds(10)).clear("conv-1");

        verify(chatMemory).clear("conv-1");
    }

    @Test
    void rend_les_jetons_consommes_et_le_motif_d_arret() {
        blockingCall();
        given(callSpec.chatResponse())
                .willReturn(response("coupé net", "max_tokens", new DefaultUsage(1200, 4096)));

        AgentAnswer answer = service(Duration.ofSeconds(10)).ask("conv-1", "ping");

        // Sans ce motif, une réponse coupée au plafond se lit comme une réponse complète.
        assertThat(answer.finishReason()).isEqualTo("max_tokens");
        assertThat(answer.usage()).isEqualTo(new AgentUsage(1200, 4096));
    }

    /** Les compteurs à zéro d'{@link EmptyUsage} diraient « rien consommé » là où rien n'est su. */
    @Test
    void ne_rend_aucun_jeton_quand_le_fournisseur_n_en_compte_pas() {
        blockingCall();
        given(callSpec.chatResponse()).willReturn(response("pong", "end_turn", new EmptyUsage()));

        assertThat(service(Duration.ofSeconds(10)).ask("conv-1", "ping").usage()).isNull();
    }

    @Test
    void rend_l_identifiant_avec_le_flux() {
        streamingCall();
        given(streamSpec.content()).willReturn(Flux.just("pong"));

        var stream = service(Duration.ofSeconds(10)).stream(null, "ping");

        // Sans identifiant rendu, la conversation créée serait inatteignable et impurgeable.
        assertThat(stream.conversationId()).isNotBlank();
        StepVerifier.create(stream.events())
                .expectNext(new AgentEvent.Token("pong"))
                .verifyComplete();
    }

    @Test
    void coupe_un_flux_muet_qui_depasse_le_plafond() {
        streamingCall();
        given(streamSpec.content()).willReturn(Flux.never());

        var stream = service(Duration.ofMillis(100)).stream("conv-1", "ping");

        StepVerifier.create(stream.events()).expectError(AgentTimeoutException.class).verify();
    }

    /**
     * Le plafond borne la durée de l'échange, pas le silence entre deux jetons : un flux qui
     * débite sans jamais se taire — vingt tours d'outils entrecoupés de texte — tiendrait sinon
     * la connexion bien au-delà de ce que kex.agent.request-timeout promet d'empêcher.
     */
    @Test
    void coupe_un_flux_bavard_qui_dure_plus_que_le_plafond() {
        streamingCall();
        given(streamSpec.content()).willReturn(Flux.interval(Duration.ofMillis(10)).map(tick -> "jeton "));

        var stream = service(Duration.ofMillis(150)).stream("conv-1", "ping");

        StepVerifier.create(stream.events())
                .thenConsumeWhile(event -> event instanceof AgentEvent.Token)
                .expectError(AgentTimeoutException.class)
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void borne_l_attente_d_un_appel_bloquant() {
        blockingCall();
        given(callSpec.chatResponse()).willAnswer(invocation -> {
            Thread.sleep(5_000);
            return response("trop tard");
        });

        AgentService service = service(Duration.ofMillis(100));

        assertThatThrownBy(() -> service.ask("conv-1", "ping"))
                .isInstanceOf(AgentTimeoutException.class)
                // L'échange continue en arrière-plan : sans cet identifiant, l'appelant ne sait
                // pas dans quelle conversation la réponse tardive va atterrir.
                .extracting(ex -> ((AgentTimeoutException) ex).conversationId()).isEqualTo("conv-1");
    }

    /**
     * L'advisor de mémoire écrit le message de l'utilisateur avant l'appel au modèle. Sur échec
     * d'un échange dont l'identifiant a été tiré ici, personne ne le connaît : l'entrée resterait
     * en mémoire sans que rien ne puisse la relire ni la purger.
     */
    @Test
    void purge_une_conversation_generee_que_l_echec_rend_inatteignable() {
        blockingCall();
        given(callSpec.chatResponse()).willThrow(new IllegalStateException("fournisseur en panne"));

        assertThatThrownBy(() -> service(Duration.ofSeconds(10)).ask(null, "ping"))
                .isInstanceOf(IllegalStateException.class);

        verify(chatMemory).clear(anyString());
    }

    @Test
    void ne_purge_pas_une_conversation_nommee_par_l_appelant() {
        blockingCall();
        given(callSpec.chatResponse()).willThrow(new IllegalStateException("fournisseur en panne"));

        assertThatThrownBy(() -> service(Duration.ofSeconds(10)).ask("conv-1", "ping"))
                .isInstanceOf(IllegalStateException.class);

        // Elle reste accessible à l'appelant, qui décide seul de ce qu'elle devient.
        verify(chatMemory, never()).clear(anyString());
    }

    /**
     * L'appel n'est pas interruptible : la réponse tardive est écrite en mémoire après le 504.
     * Purger au moment du timeout la ferait revenir juste après — d'où l'attente de la fin de la
     * tâche orpheline.
     */
    @Test
    void purge_une_conversation_generee_quand_l_appel_orphelin_se_termine() {
        blockingCall();
        given(callSpec.chatResponse()).willAnswer(invocation -> {
            Thread.sleep(200);
            return response("trop tard");
        });

        AgentService service = service(Duration.ofMillis(50));

        assertThatThrownBy(() -> service.ask(null, "ping")).isInstanceOf(AgentTimeoutException.class);

        verify(chatMemory, timeout(5_000)).clear(anyString());
    }

    @Test
    void rend_une_sortie_structuree() {
        blockingCall();
        given(callSpec.responseEntity(any(StructuredOutputConverter.class))).willReturn(
                new ResponseEntity<>(response("{}", "end_turn", new DefaultUsage(10, 20)), Map.of("total", 8)));

        var answer = service(Duration.ofSeconds(10))
                .askStructured("conv-1", "combien ?", Map.of("type", "object"));

        assertThat(answer.conversationId()).isEqualTo("conv-1");
        assertThat(answer.content()).containsEntry("total", 8);
        assertThat(answer.usage()).isEqualTo(new AgentUsage(10, 20));
        assertThat(answer.finishReason()).isEqualTo("end_turn");
    }

    @Test
    void refuse_une_sortie_structuree_sans_schema() {
        AgentService service = service(Duration.ofSeconds(10));

        assertThatThrownBy(() -> service.askStructured("c", "m", Map.of()))
                .isInstanceOf(InvalidJsonSchemaException.class);
    }

    @Test
    void echoue_vite_apres_plusieurs_pannes_du_fournisseur() {
        blockingCall();
        given(callSpec.chatResponse()).willThrow(new AgentTimeoutException(Duration.ofSeconds(1), "c"));

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
        assertThatThrownBy(() -> service.ask("c", "m")).isInstanceOf(AgentTimeoutException.class);
        assertThatThrownBy(() -> service.ask("c", "m")).isInstanceOf(AgentTimeoutException.class);
        assertThatThrownBy(() -> service.ask("c", "m")).isInstanceOf(CallNotPermittedException.class);
    }
}
