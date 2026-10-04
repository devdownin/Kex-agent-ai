// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.workspace;

import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.agent.AgentEvent;
import com.kex.agent.agent.AgentService;
import com.kex.agent.agent.AgentStream;
import com.kex.agent.config.ActorIdentity;
import com.kex.agent.execution.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceServiceTest {
    @TempDir java.nio.file.Path directory;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final AgentService agent = mock(AgentService.class);
    final Principal alice = new ActorIdentity("alice", "team");
    final Principal bob = new ActorIdentity("bob", "team");
    final Instant now = Instant.parse("2026-10-04T12:00:00Z");
    WorkspaceStore store;
    WorkspaceService service;
    ObjectProvider<TaskService> tasks;
    @BeforeEach void setup() {
        store = new WorkspaceStore(mapper, directory);
        tasks = mock(ObjectProvider.class);
        service = new WorkspaceService(store, agent, mapper, Clock.fixed(now, ZoneOffset.UTC), tasks);
        when(agent.stream(anyString(), anyString(), anyString())).thenAnswer(call -> new AgentStream(call.getArgument(1), Flux.just(new AgentEvent.Token("Réponse"))));
    }
    WorkspaceRequest.Input input(String id) { return new WorkspaceRequest.Input(id, "Vérifier les commandes", new WorkspaceRequest.Context("orders", "ce matin", "production", List.of(new WorkspaceRequest.Attachment("orders.csv", "id,etat\n1,OK")))); }
    @Test void un_autre_appareil_retrouve_les_faits_sans_rejouer_le_modele() {
        var events = service.stream(alice, input(null)).collectList().block();
        assertThat(events).extracting(e -> e.event()).containsExactly("request", "conversation", "token", "done");
        var history = new WorkspaceService(new WorkspaceStore(mapper, directory), agent, mapper, Clock.fixed(now, ZoneOffset.UTC), tasks);
        var request = history.list(alice).getFirst();
        assertThat(request.status()).isEqualTo("COMPLETE"); assertThat(request.turns().getLast().text()).isEqualTo("Réponse");
        assertThat(request.context().files().getFirst().text()).contains("1,OK");
        assertThat(history.get(alice, request.id()).conversationId()).isEqualTo(request.conversationId());
        assertThat(history.list(bob)).isEmpty();
        assertThatThrownBy(() -> history.get(bob, request.id())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> history.get(new ActorIdentity("alice", "other"), request.id())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> history.get(alice, "../../secret")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> history.list(null)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void suivre_la_conversation_preserve_le_contexte_et_les_tours() {
        service.stream(alice, input(null)).blockLast(); var first = service.list(alice).getFirst();
        service.stream(alice, new WorkspaceRequest.Input(first.id(), "Préciser", null)).blockLast();
        var full = service.get(alice, first.id()); assertThat(full.turns()).hasSize(4);
        assertThat(full.context()).isEqualTo(first.context()); assertThat(full.conversationId()).isEqualTo(first.conversationId());
        assertThat(service.list(alice).getFirst().turns()).hasSize(2);
    }
    @Test void un_flux_actif_ne_peut_pas_etre_relance_et_une_fermeture_ne_signifie_pas_reussite() {
        var sink = Sinks.many().unicast().<AgentEvent>onBackpressureBuffer();
        when(agent.stream(anyString(), anyString(), anyString())).thenAnswer(call -> new AgentStream(call.getArgument(1), sink.asFlux()));
        var subscription = service.stream(alice, input(null)).subscribe(); var request = service.list(alice).getFirst();
        assertThat(request.status()).isEqualTo("RUNNING");
        assertThatThrownBy(() -> service.stream(alice, input(request.id()))).isInstanceOf(ResponseStatusException.class);
        sink.tryEmitNext(new AgentEvent.Token("Partiel")); subscription.dispose();
        var ended = service.get(alice, request.id()); assertThat(ended.status()).isEqualTo("INTERRUPTED");
        assertThat(ended.turns().getLast().completed()).isFalse(); assertThat(ended.turns().getLast().text()).isEqualTo("Partiel");
    }
    @Test void echec_outil_clarification_erreur_et_reponse_vide_restent_distincts() {
        var clarification = "{\"kind\":\"clarification\",\"question\":\"Quand ?\",\"choices\":[{\"label\":\"Hier\",\"value\":\"Hier\"},{\"label\":\"Aujourd’hui\",\"value\":\"Aujourd’hui\"}]}";
        for (var sample : List.of(new Object[]{Flux.just(new AgentEvent.Token(clarification)), "NEEDS_INPUT"},
                new Object[]{Flux.just(new AgentEvent.ToolCall("read", 2, true), new AgentEvent.Token("Réponse")), "PARTIAL"},
                new Object[]{Flux.empty(), "PARTIAL"}, new Object[]{Flux.just(new AgentEvent.Token("{\"kind\":\"clarification\"}")), "COMPLETE"},
                new Object[]{Flux.just(new AgentEvent.Token("null")), "COMPLETE"}, new Object[]{Flux.error(new IllegalStateException("secret-provider-detail")), "ERROR"})) {
            when(agent.stream(anyString(), anyString(), anyString())).thenAnswer(call -> new AgentStream(call.getArgument(1), (Flux<AgentEvent>) sample[0]));
            service.stream(alice, input(null)).blockLast();
            assertThat(service.list(alice).stream().filter(r -> r.status().equals(sample[1]))).isNotEmpty();
        }
    }
    @Test void les_sources_serveur_sont_conservees_et_les_reponses_trop_longues_sont_bornees() {
        when(agent.stream(anyString(), anyString(), anyString())).thenAnswer(call -> new AgentStream(call.getArgument(1), Flux.just(new AgentEvent.Sources(List.of(new com.kex.agent.knowledge.KnowledgeSource("s1", "Kafka", "2026-10-04", null, "État observé"))), new AgentEvent.Token("x".repeat(64001)))));
        service.stream(alice, input(null)).blockLast(); assertThat(service.list(alice).getFirst().status()).isEqualTo("ERROR");
        assertThat(service.list(alice).getFirst().turns().getLast().sources()).extracting(s -> s.source()).containsExactly("Kafka");
        assertThatThrownBy(() -> WorkspaceService.prompt("x".repeat(32000), input(null).context())).isInstanceOf(ResponseStatusException.class);
        assertThat(WorkspaceService.prompt("Bonjour", null)).contains("autorisations");
        assertThat(WorkspaceService.prompt("Bonjour", input(null).context())).contains("orders.csv", "production", "1,OK");
    }
    @Test void un_redemarrage_recupere_une_reception_sans_fin_confirmee() {
        var r = new WorkspaceRequest(UUID.randomUUID().toString(), 0, "Avant", "conv", "RUNNING", now.minusSeconds(601), List.of(), List.of(), null, null);
        store.create(WorkspaceService.owner(alice), r);
        assertThat(service.get(alice, r.id()).status()).isEqualTo("INTERRUPTED");
        assertThat(service.get(alice, r.id()).revision()).isEqualTo(1);
    }
    @Test void un_lien_de_plan_verifie_le_locataire_et_la_revision() {
        service.stream(alice, input(null)).blockLast(); var request = service.list(alice).getFirst();
        assertThatThrownBy(() -> service.link(alice, request.id(), "task")).isInstanceOf(ResponseStatusException.class);
        var taskService = mock(TaskService.class); when(tasks.getIfAvailable()).thenReturn(taskService);
        assertThat(service.link(alice, request.id(), "task").taskId()).isEqualTo("task");
        org.mockito.Mockito.verify(taskService).get("team", "task");
        assertThatThrownBy(() -> service.link(bob, request.id(), "task")).isInstanceOf(ResponseStatusException.class);
    }
}
