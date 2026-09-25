// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@WebMvcTest(SupervisionController.class)
// Sécurité désactivée ici : elle a son propre test, ces cas visent le contrat HTTP.
@AutoConfigureMockMvc(addFilters = false)
class SupervisionControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-15T05:32:14Z");

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    SupervisionService supervision;

    @MockitoBean
    WebhookNotifier notifier;

    @Test
    void le_taux_de_pertinence_absent_se_rend_absent_et_non_a_zero() {
        // Un 0 sérialisé se lirait « l'agent se trompe toujours » là où personne n'a encore tranché.
        given(supervision.performance()).willReturn(new AgentPerformance(3, 0, 1200L, 5, 2, 4, 1, 0, 0,
                null, 1, 0, 1, 0, 0, null));

        assertThat(mvc.get().uri("/api/agent/supervision/performance"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.relevanceRate").isNull();
    }

    @Test
    void rend_la_vue_d_ensemble_en_une_requete() {
        given(supervision.overview()).willReturn(new Overview(status(AgentState.OPERATIONAL), 24, 23, 1, 0, 0,
                3, 2, List.of(), List.of(), List.of(), null, List.of(), List.of()));
        given(supervision.alerts()).willReturn(List.of());

        assertThat(mvc.get().uri("/api/agent/supervision/overview"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.processesMonitored").isEqualTo(24);
    }

    @Test
    void cree_un_processus_valide_mais_refuse_les_doublons_et_identifiants_invalides() {
        ProcessCreationRequest request = new ProcessCreationRequest("orders", "Commandes", "Vers ERP",
                "topics [orders.in, orders.out]");
        given(supervision.createProcess(any(), anyString())).willReturn(
                new MonitoredProcess("orders", "Commandes", "Vers ERP", request.hint(), null));

        assertThat(mvc.post().uri("/api/agent/supervision/processes").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"id":"orders","name":"Commandes","description":"Vers ERP",
                         "hint":"topics [orders.in, orders.out]"} """))
                .hasStatus(HttpStatus.CREATED).bodyJson().extractingPath("$.id").isEqualTo("orders");
        assertThat(mvc.post().uri("/api/agent/supervision/processes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"INVALID ID\",\"name\":\"Commandes\",\"hint\":\"topics [orders.in]\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST);

        given(supervision.createProcess(any(), anyString())).willThrow(new ProcessDefinitionConflict("orders"));
        assertThat(mvc.post().uri("/api/agent/supervision/processes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"orders\",\"name\":\"Commandes\",\"hint\":\"topics [orders.in]\"}"))
                .hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void declenche_un_cycle_et_rend_son_deroule() {
        given(supervision.runCycle(anyString())).willReturn(new CycleReport("cycle-1", NOW, NOW, 24, 3, 2, 1,
                List.of(new CycleEvent(NOW, "Analyse démarrée", "manuelle")), null));

        assertThat(mvc.post().uri("/api/agent/supervision/cycles"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.anomaliesDetected").isEqualTo(3);
    }

    @Test
    void rend_les_etapes_reelles_du_cycle_en_cours() {
        given(supervision.currentCycle()).willReturn(new CycleProgress("cycle-1", NOW,
                List.of(new CycleEvent(NOW, "Interrogation des outils", "24 processus à relever"))));

        assertThat(mvc.get().uri("/api/agent/supervision/cycles/current"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.events[0].label").isEqualTo("Interrogation des outils");
    }

    @Test
    void rend_204_quand_aucun_cycle_ne_tourne() {
        assertThat(mvc.get().uri("/api/agent/supervision/cycles/current"))
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void un_agent_en_pause_refuse_le_cycle_en_conflit_pas_en_erreur_d_appelant() {
        willThrow(new AgentPausedException()).given(supervision).runCycle(anyString());

        assertThat(mvc.post().uri("/api/agent/supervision/cycles")).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void un_cycle_deja_en_cours_ne_se_double_pas() {
        willThrow(new CycleInProgressException()).given(supervision).runCycle(anyString());

        assertThat(mvc.post().uri("/api/agent/supervision/cycles")).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void une_decision_inconnue_rend_404() {
        willThrow(new UnknownDecisionException("x")).given(supervision).decision("x");

        assertThat(mvc.get().uri("/api/agent/supervision/decisions/x")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void une_decision_deja_tranchee_rend_409() {
        willThrow(new DecisionNotPendingException("x", DecisionStatus.EXECUTED))
                .given(supervision).approve(eq("x"), anyString());

        assertThat(mvc.post().uri("/api/agent/supervision/decisions/x/approve"))
                .hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void la_liste_des_validations_en_attente_ne_se_confond_pas_avec_une_decision() {
        // /decisions/pending doit atteindre la liste, pas le {decision} qui la précède dans le code.
        given(supervision.pending()).willReturn(List.of());

        assertThat(mvc.get().uri("/api/agent/supervision/decisions/pending")).hasStatusOk();
        verify(supervision).pending();
    }

    @Test
    void approuve_et_refuse_en_citant_l_acteur() {
        given(supervision.approve(eq("d1"), anyString())).willReturn(decision(DecisionStatus.EXECUTED));
        given(supervision.reject(eq("d1"), eq("maintenance"), anyString()))
                .willReturn(decision(DecisionStatus.REJECTED));

        assertThat(mvc.post().uri("/api/agent/supervision/decisions/d1/approve"))
                .hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("EXECUTED");

        assertThat(mvc.post().uri("/api/agent/supervision/decisions/d1/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reason":"maintenance"}"""))
                .hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
    }

    @Test
    void un_refus_sans_corps_reste_possible() {
        given(supervision.reject(eq("d1"), eq(null), anyString())).willReturn(decision(DecisionStatus.REJECTED));

        assertThat(mvc.post().uri("/api/agent/supervision/decisions/d1/reject")).hasStatusOk();
    }

    @Test
    void refuse_un_seuil_de_confiance_hors_bornes() {
        assertThat(mvc.put().uri("/api/agent/supervision/policy")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"confidenceThreshold":1.4}"""))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void refuse_un_plancher_par_capacite_hors_bornes() {
        // La validation porte sur la valeur de la carte, pas seulement sur le champ scalaire.
        assertThat(mvc.put().uri("/api/agent/supervision/policy")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"confidenceThresholds":{"RESTART_CONSUMER":1.8}}"""))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void met_a_jour_la_politique() {
        given(supervision.updatePolicy(any(), anyString())).willReturn(new SupervisionPolicy("policy-v2",
                ExecutionMode.AUTOMATIC, Map.of(Capability.NOTIFY, Autonomy.AUTOMATIC), 0.7,
                Map.of(Capability.RESTART_CONSUMER, 0.95),
                new Thresholds(1000, 2.0, Duration.ofMinutes(5), 50, Duration.ofMinutes(15))));

        assertThat(mvc.put().uri("/api/agent/supervision/policy")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"mode":"AUTOMATIC","confidenceThreshold":0.7}"""))
                .hasStatusOk().bodyJson().extractingPath("$.version").isEqualTo("policy-v2");
    }

    @Test
    void teste_le_webhook_et_journalise_le_resultat() {
        given(notifier.send(anyString(), anyString())).willReturn(Optional.empty());

        assertThat(mvc.post().uri("/api/agent/supervision/notify/test"))
                .hasStatusOk().bodyJson().extractingPath("$.success").isEqualTo(true);

        verify(supervision).auditAction(anyString(), eq("Test du webhook de notification"), eq("Envoyé"));
    }

    @Test
    void rend_le_motif_d_echec_du_webhook() {
        given(notifier.send(anyString(), anyString())).willReturn(Optional.of("injoignable"));

        assertThat(mvc.post().uri("/api/agent/supervision/notify/test"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.detail").isEqualTo("injoignable");
    }

    @Test
    void declare_une_fenetre_de_maintenance() {
        MaintenanceWindow window = new MaintenanceWindow("order-integration", "Order Integration",
                NOW.plusSeconds(7200), "Déploiement", "opérateur");
        given(supervision.declareMaintenance(eq("order-integration"), eq(Duration.ofHours(2)),
                eq("Déploiement"), anyString())).willReturn(window);

        assertThat(mvc.post().uri("/api/agent/supervision/processes/order-integration/maintenance")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"duration":"PT2H","reason":"Déploiement"}"""))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.processId").isEqualTo("order-integration");
    }

    @Test
    void leve_une_fenetre_de_maintenance() {
        assertThat(mvc.delete().uri("/api/agent/supervision/processes/order-integration/maintenance"))
                .hasStatus(HttpStatus.NO_CONTENT);

        verify(supervision).endMaintenance(eq("order-integration"), anyString());
    }

    @Test
    void une_maintenance_sur_un_processus_inconnu_rend_404() {
        willThrow(new UnknownProcessException("x")).given(supervision)
                .declareMaintenance(eq("x"), any(), any(), anyString());

        assertThat(mvc.post().uri("/api/agent/supervision/processes/x/maintenance")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"duration":"PT1H"}"""))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void rend_la_tendance_d_un_processus() {
        given(supervision.processHistory("order-integration")).willReturn(List.of(
                new ProcessHistoryPoint("cycle-1", NOW, ProcessState.WARNING, 90_000L)));

        assertThat(mvc.get().uri("/api/agent/supervision/processes/order-integration/history"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$[0].state").isEqualTo("WARNING");
    }

    @Test
    void met_en_pause_et_reprend() {
        given(supervision.pause(anyString())).willReturn(status(AgentState.PAUSED));
        given(supervision.resume(anyString())).willReturn(status(AgentState.OPERATIONAL));

        assertThat(mvc.post().uri("/api/agent/supervision/pause"))
                .hasStatusOk().bodyJson().extractingPath("$.state").isEqualTo("PAUSED");
        assertThat(mvc.post().uri("/api/agent/supervision/resume"))
                .hasStatusOk().bodyJson().extractingPath("$.state").isEqualTo("OPERATIONAL");
    }

    @Test
    void expose_processus_anomalies_cycles_et_audit() {
        given(supervision.snapshots()).willReturn(List.of());
        given(supervision.alerts()).willReturn(List.of());
        given(supervision.performance()).willReturn(new AgentPerformance(0, 0, null, 0, 0, 0, 0, 0, 0,
                null, 0, 0, 0, 0, 0, null));
        given(supervision.cycles()).willReturn(List.of());
        given(supervision.audit()).willReturn(List.of());
        given(supervision.decisions()).willReturn(List.of());
        given(supervision.status()).willReturn(status(AgentState.OPERATIONAL));

        for (String path : List.of("processes", "alerts", "cycles", "audit", "decisions", "status",
                "performance")) {
            assertThat(mvc.get().uri("/api/agent/supervision/" + path)).hasStatusOk();
        }
    }

    private static AgentStatus status(AgentState state) {
        return new AgentStatus(state, ExecutionMode.SUPERVISED, state == AgentState.PAUSED, false, NOW,
                "cycle-1", null, "policy-v1", 0.85, null, List.of());
    }

    private static Decision decision(DecisionStatus status) {
        return new Decision("d1", "cycle-1", "a1", "order-integration", "Order Integration",
                Capability.RESTART_CONSUMER, "objectif", "contexte", "Redémarrer", List.of(), "Faible",
                0.96, status, "ok", "policy-v1", "corr", "opérateur", NOW, NOW, NOW.plusSeconds(1800));
    }
}
