// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.kex.agent.agent.AgentService;
import com.kex.agent.agent.AgentStructuredAnswer;
import com.kex.agent.mcp.McpToolCatalog;
import com.kex.agent.mcp.McpToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupervisionServiceTest {

    private static final MonitoredProcess ORDERS =
            new MonitoredProcess("order-integration", "Order Integration", "Commandes", "topic integration.events");

    private final AgentService agentService = mock(AgentService.class);
    private final McpToolCatalog toolCatalog = mock(McpToolCatalog.class);
    private MutableClock clock;

    @BeforeEach
    void horloge() {
        clock = new MutableClock(Instant.parse("2026-09-15T05:32:14Z"));
    }

    @Test
    void sans_processus_declare_le_cycle_le_dit_au_lieu_d_inventer() {
        SupervisionService service = service(properties(List.of(), Map.of(), Map.of()));

        CycleReport report = service.runCycle("test");

        assertThat(report.processesAnalysed()).isZero();
        assertThat(report.failure()).isNull();
        assertThat(report.events()).extracting(CycleEvent::label).contains("Aucun processus déclaré");
        verify(agentService, never()).askStructured(anyString(), anyString(), any());
    }

    @Test
    void une_anomalie_sur_une_capacite_supervisee_attend_une_validation() {
        analysisReturns(anomalyPayload("RESTART_CONSUMER", 0.96));
        SupervisionService service = service(properties(List.of(ORDERS),
                Map.of(Capability.RESTART_CONSUMER, Autonomy.SUPERVISED), Map.of()));

        CycleReport report = service.runCycle("test");

        assertThat(report.anomaliesDetected()).isEqualTo(1);
        assertThat(report.pendingApprovals()).isEqualTo(1);
        assertThat(service.pending()).singleElement()
                .satisfies(decision -> {
                    assertThat(decision.status()).isEqualTo(DecisionStatus.PENDING_APPROVAL);
                    assertThat(decision.estimatedImpact()).isNotBlank();
                    assertThat(decision.observations()).isNotEmpty();
                });
        // Rien n'a été exécuté tant qu'un humain n'a pas tranché.
        verify(toolCatalog, never()).call(anyString(), anyString(), any());
    }

    @Test
    void une_capacite_interdite_reste_une_recommandation() {
        analysisReturns(anomalyPayload("MODIFY_CONFIGURATION", 0.99));
        SupervisionService service = service(properties(List.of(ORDERS),
                Map.of(Capability.MODIFY_CONFIGURATION, Autonomy.FORBIDDEN), Map.of()));

        service.runCycle("test");

        assertThat(service.decisions()).singleElement()
                .extracting(Decision::status).isEqualTo(DecisionStatus.BLOCKED);
        verify(toolCatalog, never()).call(anyString(), anyString(), any());
    }

    @Test
    void une_capacite_automatique_s_execute_au_dessus_du_seuil() {
        analysisReturns(anomalyPayload("RESTART_CONSUMER", 0.96));
        when(toolCatalog.call(eq("kafka-explorer"), eq("restart"), any()))
                .thenReturn(new McpToolResult("kafka-explorer", "restart", false, List.of("lag 850"), null));

        SupervisionService service = service(properties(ExecutionMode.AUTOMATIC, List.of(ORDERS),
                Map.of(Capability.RESTART_CONSUMER, Autonomy.AUTOMATIC),
                Map.of(Capability.RESTART_CONSUMER, new ActionBinding("kafka-explorer", "restart", Map.of()))));

        service.runCycle("test");

        assertThat(service.decisions()).singleElement().satisfies(decision -> {
            assertThat(decision.status()).isEqualTo(DecisionStatus.EXECUTED);
            assertThat(decision.result()).isEqualTo("lag 850");
        });
    }

    @Test
    void sous_le_seuil_de_confiance_l_automatique_repasse_par_un_humain() {
        analysisReturns(anomalyPayload("RESTART_CONSUMER", 0.42));
        SupervisionService service = service(properties(ExecutionMode.AUTOMATIC, List.of(ORDERS),
                Map.of(Capability.RESTART_CONSUMER, Autonomy.AUTOMATIC),
                Map.of(Capability.RESTART_CONSUMER, new ActionBinding("kafka-explorer", "restart", Map.of()))));

        service.runCycle("test");

        assertThat(service.pending()).singleElement()
                .extracting(Decision::result).asString().contains("sous le plancher de 85 %");
        verify(toolCatalog, never()).call(anyString(), anyString(), any());
    }

    @Test
    void un_plancher_de_confiance_par_capacite_bloque_ce_que_le_plancher_global_laissait_passer() {
        // 0,90 de confiance : au-dessus du plancher global de 0,85, sous celui de la capacité.
        analysisReturns(anomalyPayload("RESTART_CONSUMER", 0.90));
        SupervisionService service = service(properties(ExecutionMode.AUTOMATIC, List.of(ORDERS),
                Map.of(Capability.RESTART_CONSUMER, Autonomy.AUTOMATIC),
                Map.of(Capability.RESTART_CONSUMER, new ActionBinding("kafka-explorer", "restart", Map.of())),
                Map.of(Capability.RESTART_CONSUMER, 0.95)));

        service.runCycle("test");

        assertThat(service.pending()).singleElement()
                .extracting(Decision::result).asString()
                .contains("sous le plancher de 95 %").contains("RESTART_CONSUMER");
        verify(toolCatalog, never()).call(anyString(), anyString(), any());
    }

    @Test
    void au_dessus_du_plancher_de_la_capacite_l_action_part() {
        analysisReturns(anomalyPayload("RESTART_CONSUMER", 0.97));
        when(toolCatalog.call(anyString(), anyString(), any()))
                .thenReturn(new McpToolResult("kafka-explorer", "restart", false, List.of("lag 850"), null));
        SupervisionService service = service(properties(ExecutionMode.AUTOMATIC, List.of(ORDERS),
                Map.of(Capability.RESTART_CONSUMER, Autonomy.AUTOMATIC),
                Map.of(Capability.RESTART_CONSUMER, new ActionBinding("kafka-explorer", "restart", Map.of())),
                Map.of(Capability.RESTART_CONSUMER, 0.95)));

        service.runCycle("test");

        assertThat(service.decisions()).singleElement()
                .extracting(Decision::status).isEqualTo(DecisionStatus.EXECUTED);
    }

    @Test
    void un_plancher_par_capacite_ne_peut_pas_abaisser_le_plancher_global() {
        SupervisionPolicy policy = new SupervisionPolicy("v", ExecutionMode.AUTOMATIC,
                Map.of(), 0.85, Map.of(Capability.NOTIFY, 0.40, Capability.RESTART_CONSUMER, 0.95),
                thresholds());

        // Un réglage plus permissif est ignoré : il affaiblirait en silence la garantie globale.
        assertThat(policy.confidenceThresholdOf(Capability.NOTIFY)).isEqualTo(0.85);
        assertThat(policy.confidenceThresholdOf(Capability.RESTART_CONSUMER)).isEqualTo(0.95);
        // Une capacité sans réglage propre suit le plancher global.
        assertThat(policy.confidenceThresholdOf(Capability.REPLAY_MESSAGES)).isEqualTo(0.85);
    }

    @Test
    void le_mode_ne_peut_que_restreindre_l_autonomie_declaree() {
        SupervisionPolicy supervised = new SupervisionPolicy("v", ExecutionMode.SUPERVISED,
                Map.of(Capability.NOTIFY, Autonomy.AUTOMATIC), 0.8, Map.of(), thresholds());
        assertThat(supervised.effectiveAutonomy(Capability.NOTIFY)).isEqualTo(Autonomy.SUPERVISED);

        SupervisionPolicy manual = new SupervisionPolicy("v", ExecutionMode.MANUAL,
                Map.of(Capability.NOTIFY, Autonomy.AUTOMATIC, Capability.REPLAY_MESSAGES, Autonomy.FORBIDDEN),
                0.8, Map.of(), thresholds());
        assertThat(manual.effectiveAutonomy(Capability.NOTIFY)).isEqualTo(Autonomy.SUPERVISED);
        // Le mode manuel n'autorise rien : une capacité interdite le reste.
        assertThat(manual.effectiveAutonomy(Capability.REPLAY_MESSAGES)).isEqualTo(Autonomy.FORBIDDEN);

        SupervisionPolicy automatic = new SupervisionPolicy("v", ExecutionMode.AUTOMATIC,
                Map.of(Capability.NOTIFY, Autonomy.SUPERVISED), 0.8, Map.of(), thresholds());
        assertThat(automatic.effectiveAutonomy(Capability.NOTIFY)).isEqualTo(Autonomy.SUPERVISED);
        // Une capacité absente de la politique est interdite, pas permissive.
        assertThat(automatic.effectiveAutonomy(Capability.MODIFY_CONFIGURATION)).isEqualTo(Autonomy.FORBIDDEN);
    }

    @Test
    void une_validation_sans_outil_lie_echoue_explicitement() {
        analysisReturns(anomalyPayload("RESTART_CONSUMER", 0.9));
        SupervisionService service = service(properties(List.of(ORDERS),
                Map.of(Capability.RESTART_CONSUMER, Autonomy.SUPERVISED), Map.of()));
        service.runCycle("test");
        String id = service.pending().getFirst().id();

        Decision approved = service.approve(id, "opérateur");

        assertThat(approved.status()).isEqualTo(DecisionStatus.FAILED);
        assertThat(approved.result()).contains("Aucun outil MCP lié");
    }

    @Test
    void un_refus_conserve_son_motif_dans_l_audit() {
        analysisReturns(anomalyPayload("RESTART_CONSUMER", 0.9));
        SupervisionService service = service(properties(List.of(ORDERS),
                Map.of(Capability.RESTART_CONSUMER, Autonomy.SUPERVISED), Map.of()));
        service.runCycle("test");
        String id = service.pending().getFirst().id();

        Decision rejected = service.reject(id, "Maintenance planifiée", "opérateur");

        assertThat(rejected.status()).isEqualTo(DecisionStatus.REJECTED);
        assertThat(service.audit()).anySatisfy(entry -> {
            assertThat(entry.actor()).isEqualTo("opérateur");
            assertThat(entry.reason()).isEqualTo("Maintenance planifiée");
            assertThat(entry.decisionId()).isEqualTo(id);
        });
        assertThatThrownBy(() -> service.approve(id, "opérateur"))
                .isInstanceOf(DecisionNotPendingException.class);
    }

    @Test
    void une_demande_de_validation_expire() {
        analysisReturns(anomalyPayload("RESTART_CONSUMER", 0.9));
        SupervisionService service = service(properties(List.of(ORDERS),
                Map.of(Capability.RESTART_CONSUMER, Autonomy.SUPERVISED), Map.of()));
        service.runCycle("test");
        String id = service.pending().getFirst().id();

        clock.advance(Duration.ofHours(3));

        assertThat(service.pending()).isEmpty();
        // EXPIRED et non FAILED : « personne n'a répondu » n'est pas « l'outil a échoué ».
        assertThat(service.decision(id).status()).isEqualTo(DecisionStatus.EXPIRED);
        assertThat(service.decision(id).result()).contains("expirée");
        assertThat(service.decision(id).resolvedBy()).isEqualTo("Système");
    }

    @Test
    void un_agent_en_pause_ne_lance_aucun_cycle() {
        SupervisionService service = service(properties(List.of(ORDERS), Map.of(), Map.of()));

        assertThat(service.pause("opérateur").state()).isEqualTo(AgentState.PAUSED);
        assertThatThrownBy(() -> service.runCycle("opérateur")).isInstanceOf(AgentPausedException.class);
        assertThat(service.resume("opérateur").paused()).isFalse();
    }

    @Test
    void un_cycle_en_echec_est_conserve_et_bascule_l_etat_en_erreur() {
        when(agentService.askStructured(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("modèle injoignable"));
        SupervisionService service = service(properties(List.of(ORDERS), Map.of(), Map.of()));

        CycleReport report = service.runCycle("test");

        assertThat(report.failure()).isEqualTo("modèle injoignable");
        assertThat(service.status().state()).isEqualTo(AgentState.ERROR);
        // La conversation jetable du cycle est purgée même quand l'analyse échoue.
        verify(agentService).clear(anyString());
    }

    @Test
    void des_donnees_trop_vieilles_degradent_l_etat_sans_le_masquer() {
        analysisReturns(Map.of("processes",
                List.of(Map.of("processId", "order-integration", "state", "OK")), "anomalies", List.of()));
        SupervisionService service = service(properties(List.of(ORDERS), Map.of(), Map.of()));
        service.runCycle("test");

        assertThat(service.status().state()).isEqualTo(AgentState.OPERATIONAL);
        assertThat(service.status().staleSince()).isNull();

        clock.advance(Duration.ofMinutes(20));

        assertThat(service.status().state()).isEqualTo(AgentState.DEGRADED);
        assertThat(service.status().staleSince()).isNotNull();
    }

    @Test
    void un_processus_absent_de_la_reponse_reste_affiche_en_inconnu() {
        analysisReturns(Map.of("processes", List.of(), "anomalies", List.of()));
        SupervisionService service = service(properties(List.of(ORDERS), Map.of(), Map.of()));

        service.runCycle("test");

        assertThat(service.snapshots()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.processId()).isEqualTo("order-integration");
            assertThat(snapshot.state()).isEqualTo(ProcessState.UNKNOWN);
        });
        assertThat(service.overview().processesUnknown()).isEqualTo(1);
    }

    @Test
    void deux_cycles_voyant_le_meme_symptome_ne_font_qu_une_alerte() {
        analysisReturns(anomalyPayload("RESTART_CONSUMER", 0.9));
        SupervisionService service = service(properties(List.of(ORDERS),
                Map.of(Capability.RESTART_CONSUMER, Autonomy.SUPERVISED), Map.of()));

        service.runCycle("test");
        clock.advance(Duration.ofMinutes(1));
        service.runCycle("test");

        assertThat(service.alerts()).singleElement().satisfies(alert -> {
            assertThat(alert.title()).isEqualTo("Retard de traitement");
            assertThat(alert.occurrences()).isEqualTo(2);
            assertThat(alert.firstSeenAt()).isBefore(alert.lastSeenAt());
            // L'alerte porte l'action à prendre : la chercher ailleurs coûterait un aller-retour.
            assertThat(alert.pendingDecisionId()).isNotNull();
        });
    }

    @Test
    void une_alerte_que_le_dernier_cycle_ne_revoit_plus_sort_de_la_liste() {
        analysisReturns(anomalyPayload("RESTART_CONSUMER", 0.9));
        SupervisionService service = service(properties(List.of(ORDERS),
                Map.of(Capability.RESTART_CONSUMER, Autonomy.SUPERVISED), Map.of()));
        service.runCycle("test");
        assertThat(service.alerts()).hasSize(1);

        // Deuxième cycle sans anomalie : le symptôme a cessé d'être vrai.
        analysisReturns(Map.of("processes",
                List.of(Map.of("processId", "order-integration", "state", "OK")), "anomalies", List.of()));
        service.runCycle("test");

        assertThat(service.alerts()).isEmpty();
    }

    @Test
    void une_erreur_passe_devant_un_avertissement_qui_se_repete() {
        analysisReturns(Map.of("processes", List.of(), "anomalies", List.of(
                Map.of("processId", "order-integration", "title", "Retard", "severity", "WARNING",
                        "confidence", 0.8),
                Map.of("processId", "order-integration", "title", "Timeout", "severity", "ERROR",
                        "confidence", 0.9))));
        SupervisionService service = service(properties(List.of(ORDERS), Map.of(), Map.of()));
        service.runCycle("test");
        service.runCycle("test");

        assertThat(service.alerts()).extracting(Alert::title).containsExactly("Timeout", "Retard");
    }

    @Test
    void la_mesure_de_l_agent_ne_devine_pas_ce_qu_elle_ne_sait_pas() {
        analysisReturns(anomalyPayload("RESTART_CONSUMER", 0.9));
        SupervisionService service = service(properties(List.of(ORDERS),
                Map.of(Capability.RESTART_CONSUMER, Autonomy.SUPERVISED), Map.of()));
        service.runCycle("test");

        // Personne n'a tranché : le taux de pertinence est absent, pas à zéro.
        assertThat(service.performance()).satisfies(perf -> {
            assertThat(perf.cycles()).isEqualTo(1);
            assertThat(perf.decisionsTaken()).isEqualTo(1);
            assertThat(perf.relevanceRate()).isNull();
            assertThat(perf.autonomousDecisions()).isZero();
            assertThat(perf.averageResolutionMillis()).isNull();
        });

        service.reject(service.pending().getFirst().id(), "faux positif", "opérateur");

        assertThat(service.performance()).satisfies(perf -> {
            assertThat(perf.humanRejections()).isEqualTo(1);
            assertThat(perf.relevanceRate()).isZero();
            assertThat(perf.averageResolutionMillis()).isNotNull();
        });
    }

    @Test
    void une_execution_autonome_n_entre_pas_dans_le_taux_de_pertinence() {
        // L'agent ne se confirme pas lui-même : seul un verdict humain compte.
        analysisReturns(anomalyPayload("NOTIFY", 0.99));
        when(toolCatalog.call(anyString(), anyString(), any()))
                .thenReturn(new McpToolResult("c", "t", false, List.of("prévenu"), null));
        SupervisionService service = service(properties(ExecutionMode.AUTOMATIC, List.of(ORDERS),
                Map.of(Capability.NOTIFY, Autonomy.AUTOMATIC),
                Map.of(Capability.NOTIFY, new ActionBinding("c", "t", Map.of()))));
        service.runCycle("test");

        assertThat(service.performance()).satisfies(perf -> {
            assertThat(perf.autonomousDecisions()).isEqualTo(1);
            assertThat(perf.actionsExecuted()).isEqualTo(1);
            assertThat(perf.humanApprovals()).isZero();
            assertThat(perf.relevanceRate()).isNull();
        });
    }

    @Test
    void la_politique_se_versionne_et_s_audite() {
        SupervisionService service = service(properties(List.of(ORDERS), Map.of(), Map.of()));
        assertThat(service.policy().version()).isEqualTo("policy-v1");

        SupervisionPolicy updated = service.updatePolicy(new PolicyUpdate(ExecutionMode.AUTOMATIC,
                Map.of(Capability.NOTIFY, Autonomy.AUTOMATIC), 0.7,
                Map.of(Capability.RESTART_CONSUMER, 0.95), null, "montée en autonomie"), "opérateur");

        assertThat(updated.version()).isEqualTo("policy-v2");
        assertThat(updated.mode()).isEqualTo(ExecutionMode.AUTOMATIC);
        assertThat(updated.confidenceThreshold()).isEqualTo(0.7);
        assertThat(updated.confidenceThresholdOf(Capability.RESTART_CONSUMER)).isEqualTo(0.95);
        // Les seuils non fournis restent ceux d'avant.
        assertThat(updated.thresholds()).isEqualTo(thresholds());
        assertThat(service.audit()).anySatisfy(entry ->
                assertThat(entry.reason()).isEqualTo("montée en autonomie"));
    }

    @Test
    void une_decision_inconnue_ne_se_devine_pas() {
        SupervisionService service = service(properties(List.of(ORDERS), Map.of(), Map.of()));
        assertThatThrownBy(() -> service.decision("inexistante")).isInstanceOf(UnknownDecisionException.class);
        assertThatThrownBy(() -> service.approve("inexistante", "x")).isInstanceOf(UnknownDecisionException.class);
    }

    @Test
    void un_outil_en_erreur_ne_passe_pas_pour_une_reussite() {
        analysisReturns(anomalyPayload("NOTIFY", 0.99));
        when(toolCatalog.call(anyString(), anyString(), any()))
                .thenReturn(new McpToolResult("c", "t", true, List.of("destinataire inconnu"), null));
        SupervisionService service = service(properties(ExecutionMode.AUTOMATIC, List.of(ORDERS),
                Map.of(Capability.NOTIFY, Autonomy.AUTOMATIC),
                Map.of(Capability.NOTIFY, new ActionBinding("c", "t", Map.of("channel", "ops")))));

        service.runCycle("test");

        assertThat(service.decisions()).singleElement().satisfies(decision -> {
            assertThat(decision.status()).isEqualTo(DecisionStatus.FAILED);
            assertThat(decision.result()).isEqualTo("destinataire inconnu");
        });
    }

    @Test
    void une_anomalie_sans_capacite_retombe_sur_la_notification() {
        analysisReturns(Map.of("processes", List.of(Map.of("processId", "order-integration", "state", "WARNING")),
                "anomalies", List.of(Map.of("processId", "order-integration", "title", "Retard",
                        "severity", "WARNING", "confidence", 0.8,
                        "observations", List.of(Map.of("label", "lag", "value", "12421"))))));
        SupervisionService service = service(properties(List.of(ORDERS), Map.of(), Map.of()));

        service.runCycle("test");

        assertThat(service.decisions()).singleElement()
                .extracting(Decision::capability).isEqualTo(Capability.NOTIFY);
    }

    /* ── Outillage ─────────────────────────────────────────────────────── */

    private SupervisionService service(SupervisionProperties properties) {
        return new SupervisionService(agentService, toolCatalog, properties, clock);
    }

    private void analysisReturns(Map<String, Object> content) {
        when(agentService.askStructured(anyString(), anyString(), any()))
                .thenReturn(new AgentStructuredAnswer("cycle", content, List.of()));
    }

    private static Map<String, Object> anomalyPayload(String capability, double confidence) {
        return Map.of(
                "processes", List.of(Map.of("processId", "order-integration", "state", "WARNING",
                        "delayMillis", 480_000, "note", "Retard de 8 minutes")),
                "anomalies", List.of(Map.of(
                        "processId", "order-integration",
                        "title", "Retard de traitement",
                        "severity", "WARNING",
                        "observations", List.of(Map.of("label", "Consumer lag", "value", "12421")),
                        "analysis", "Le rythme de consommation est inférieur au rythme de production",
                        "probableCause", "Consumer sous-dimensionné",
                        "confidence", confidence,
                        "recommendation", "Redémarrer Consumer Integration-02",
                        "capability", capability)));
    }

    private static Thresholds thresholds() {
        return new Thresholds(1000, 2.0, Duration.ofMinutes(5), 50, Duration.ofMinutes(15));
    }

    private static SupervisionProperties properties(List<MonitoredProcess> processes,
                                                    Map<Capability, Autonomy> autonomy,
                                                    Map<Capability, ActionBinding> actions) {
        return properties(ExecutionMode.SUPERVISED, processes, autonomy, actions);
    }

    /** Le mode fait partie du scénario : sous SUPERVISED, une capacité automatique est bridée. */
    private static SupervisionProperties properties(ExecutionMode mode, List<MonitoredProcess> processes,
                                                    Map<Capability, Autonomy> autonomy,
                                                    Map<Capability, ActionBinding> actions) {
        return properties(mode, processes, autonomy, actions, Map.of());
    }

    private static SupervisionProperties properties(ExecutionMode mode, List<MonitoredProcess> processes,
                                                    Map<Capability, Autonomy> autonomy,
                                                    Map<Capability, ActionBinding> actions,
                                                    Map<Capability, Double> floors) {
        return new SupervisionProperties(true, processes, mode, 0.85, thresholds(),
                autonomy, floors, actions, 200, Duration.ofMinutes(30), Duration.ofMinutes(15));
    }

    /** Horloge pilotable : l'expiration et la péremption se testent en avançant, pas en attendant. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
