// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import com.kex.agent.agent.AgentService;
import com.kex.agent.agent.AgentStructuredAnswer;
import com.kex.agent.agent.TokenBudgetService;
import com.kex.agent.mcp.McpToolCatalog;
import com.kex.agent.mcp.McpToolResult;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupervisionServiceTest {

    private static final MonitoredProcess ORDERS =
            new MonitoredProcess("order-integration", "Order Integration", "Commandes", "topic integration.events", null);

    private final AgentService agentService = mock(AgentService.class);
    private final McpToolCatalog toolCatalog = mock(McpToolCatalog.class);
    private final WebhookNotifier notifier = mock(WebhookNotifier.class);
    private final TokenBudgetService tokenBudget = mock(TokenBudgetService.class);
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
    void correle_l_audit_a_la_trace_en_cours() {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext traceContext = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn("trace-42");
        SupervisionService service = new SupervisionService(agentService, toolCatalog,
                properties(List.of(), Map.of(), Map.of()), clock, keyMissing, tracer,
                CircuitBreakerRegistry.ofDefaults(), new InMemoryAuditRepository(200), notifier, tokenBudget);

        service.pause("opérateur");

        assertThat(service.audit()).singleElement().extracting(AuditEntry::traceId).isEqualTo("trace-42");
    }

    @Test
    void ne_correle_rien_hors_d_une_trace_en_cours() {
        SupervisionService service = new SupervisionService(agentService, toolCatalog,
                properties(List.of(), Map.of(), Map.of()), clock, keyMissing, mock(Tracer.class),
                CircuitBreakerRegistry.ofDefaults(), new InMemoryAuditRepository(200), notifier, tokenBudget);

        service.pause("opérateur");

        assertThat(service.audit()).singleElement().extracting(AuditEntry::traceId).isNull();
    }

    @Test
    void expose_l_etat_des_disjoncteurs_dans_le_statut() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        registry.circuitBreaker("mcp-tool");
        SupervisionService service = new SupervisionService(agentService, toolCatalog,
                properties(List.of(), Map.of(), Map.of()), clock, keyMissing, mock(Tracer.class),
                registry, new InMemoryAuditRepository(200), notifier, tokenBudget);

        assertThat(service.status().circuitBreakers())
                .extracting(CircuitBreakerStatus::name)
                .contains("mcp-tool");
    }

    /**
     * Deux requêtes concurrentes sur la même décision : sans verrou, toutes deux liraient
     * PENDING_APPROVAL avant que l'une n'ait écrit son résultat, et exécuteraient l'action deux fois.
     */
    @Test
    void refuse_une_seconde_approbation_concurrente_de_la_meme_decision() throws Exception {
        analysisReturns(anomalyPayload("RESTART_CONSUMER", 0.9));
        SupervisionService service = service(properties(List.of(ORDERS),
                Map.of(Capability.RESTART_CONSUMER, Autonomy.SUPERVISED),
                Map.of(Capability.RESTART_CONSUMER, new ActionBinding("faux", "tool", Map.of()))));
        service.runCycle("test");
        String id = service.pending().getFirst().id();

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(toolCatalog.call(eq("faux"), eq("tool"), any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return new McpToolResult("faux", "tool", false, List.of("pong"), null);
        });

        Thread first = new Thread(() -> service.approve(id, "opérateur"));
        first.start();
        entered.await();

        assertThatThrownBy(() -> service.approve(id, "opérateur"))
                .isInstanceOf(DecisionInProgressException.class);

        release.countDown();
        first.join();

        assertThat(service.decisions()).singleElement()
                .extracting(Decision::status).isEqualTo(DecisionStatus.EXECUTED);
        verify(toolCatalog, org.mockito.Mockito.times(1)).call(eq("faux"), eq("tool"), any());
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

    /** Clé présente par défaut : sans quoi chaque cas existant basculerait en DÉGRADÉ. */
    private ModelAvailability keyMissing = () -> false;

    @Test
    void sans_cle_de_modele_l_agent_n_est_pas_operationnel() {
        // Le défaut que ce cas verrouille : l'état ne regardait que les cycles, donc une instance
        // incapable du moindre échange affichait OPÉRATIONNEL en tête d'écran, au-dessus d'un
        // bandeau qui disait « Aucune analyse exécutée ».
        keyMissing = () -> true;
        SupervisionService service = service(properties(List.of(ORDERS), Map.of(), Map.of()));

        AgentStatus status = service.status();

        assertThat(status.state()).isEqualTo(AgentState.DEGRADED);
        assertThat(status.stateReason()).contains("Aucune clé");
    }

    @Test
    void jamais_analyse_vaut_inconnu_et_non_operationnel() {
        // Rien n'a été mesuré : l'agent n'affirme pas que tout va bien, il dit qu'il ne sait pas.
        AgentStatus status = service(properties(List.of(ORDERS), Map.of(), Map.of())).status();

        assertThat(status.state()).isEqualTo(AgentState.UNKNOWN);
        assertThat(status.lastCycleAt()).isNull();
        assertThat(status.stateReason()).contains("Aucune analyse");
    }

    @Test
    void un_cycle_en_echec_prime_sur_l_absence_de_cle() {
        // L'échec est un fait constaté, l'absence de clé une cause probable : c'est le fait qui
        // s'affiche, et l'écran Configuration qui nomme la cause.
        keyMissing = () -> true;
        when(agentService.askStructured(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("401 Unauthorized"));
        SupervisionService service = service(properties(List.of(ORDERS), Map.of(), Map.of()));

        service.runCycle("test");

        assertThat(service.status().state()).isEqualTo(AgentState.ERROR);
        // Le détail brut du fournisseur (ici « 401 Unauthorized ») reste dans le déroulé du cycle
        // et l'audit — voir CycleReport.failure() — pas dans le bandeau d'état de la console.
        assertThat(service.status().stateReason()).doesNotContain("401 Unauthorized");
        assertThat(service.status().stateReason()).contains("Dernier cycle en échec");
    }

    @Test
    void une_pause_prime_sur_tout_le_reste() {
        keyMissing = () -> true;
        SupervisionService service = service(properties(List.of(ORDERS), Map.of(), Map.of()));

        assertThat(service.pause("opérateur").state()).isEqualTo(AgentState.PAUSED);
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
    void un_releve_partiel_degrade_l_agent_au_lieu_de_le_laisser_vert() {
        // Bout en bout : un OK rendu sur une passe incomplète ne doit pas produire un cycle vert.
        // Sans cela, l'anomalie restée dans ce qui n'a pas été lu passe inaperçue.
        analysisReturns(Map.of("processes", List.of(Map.of(
                "processId", "order-integration", "state", "OK",
                "coverage", Map.of("complete", false, "stopReason", "TIME_BUDGET",
                        "notReached", List.of("demo.orders.3.enriched")))),
                "anomalies", List.of()));
        SupervisionService service = service(properties(List.of(ORDERS), Map.of(), Map.of()));

        service.runCycle("test");

        assertThat(service.snapshots()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.state()).isEqualTo(ProcessState.UNKNOWN);
            assertThat(snapshot.coverage().notReached()).containsExactly("demo.orders.3.enriched");
        });
        assertThat(service.overview().processesOk()).isZero();
        assertThat(service.overview().processesUnknown()).isEqualTo(1);
        // L'état de l'agent le dit aussi : on ne sait pas, donc on n'est pas opérationnel.
        assertThat(service.status().state()).isEqualTo(AgentState.DEGRADED);
    }

    @Test
    void une_passe_complete_laisse_l_agent_operationnel() {
        analysisReturns(Map.of("processes", List.of(Map.of(
                "processId", "order-integration", "state", "OK",
                "coverage", Map.of("complete", true, "stopReason", "EXHAUSTED"))),
                "anomalies", List.of()));
        SupervisionService service = service(properties(List.of(ORDERS), Map.of(), Map.of()));

        service.runCycle("test");

        assertThat(service.status().state()).isEqualTo(AgentState.OPERATIONAL);
        assertThat(service.overview().processesOk()).isEqualTo(1);
    }

    @Test
    void le_prompt_dit_au_modele_de_lire_la_couverture() {
        // La règle ne tient que si le modèle sait qu'il doit rendre l'enveloppe : sans cette
        // consigne, il n'y a rien à interpréter en aval et la correction est sans effet.
        analysisReturns(Map.of("processes", List.of(), "anomalies", List.of()));
        SupervisionService service = service(properties(List.of(ORDERS), Map.of(), Map.of()));

        service.runCycle("test");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(agentService).askStructured(anyString(), prompt.capture(), any());
        assertThat(prompt.getValue())
                .contains("coverage")
                .contains("EXHAUSTED")
                .contains("topicsNotReached")
                .contains("measured")
                .contains("resumeToken");
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

    @Test
    void notifie_par_webhook_quand_aucun_outil_n_est_lie_a_notify() {
        analysisReturns(anomalyPayload("NOTIFY", 0.99));
        SupervisionService service = service(properties(ExecutionMode.AUTOMATIC, List.of(ORDERS),
                Map.of(Capability.NOTIFY, Autonomy.AUTOMATIC), Map.of()));

        service.runCycle("test");

        assertThat(service.decisions()).singleElement().satisfies(decision -> {
            assertThat(decision.status()).isEqualTo(DecisionStatus.EXECUTED);
            assertThat(decision.result()).isEqualTo("Notifié par webhook");
        });
        verify(notifier).send(anyString(), anyString());
    }

    @Test
    void signale_l_echec_du_webhook_de_notification() {
        analysisReturns(anomalyPayload("NOTIFY", 0.99));
        SupervisionService service = service(properties(ExecutionMode.AUTOMATIC, List.of(ORDERS),
                Map.of(Capability.NOTIFY, Autonomy.AUTOMATIC), Map.of()));
        // Après service(...) : son stub par défaut (succès) est sinon le dernier enregistré, donc
        // celui qui gagne — Mockito retient le stub le plus récent quand deux matchers se recoupent.
        given(notifier.send(anyString(), anyString())).willReturn(Optional.of("connexion refusée"));

        service.runCycle("test");

        assertThat(service.decisions()).singleElement().satisfies(decision -> {
            assertThat(decision.status()).isEqualTo(DecisionStatus.FAILED);
            assertThat(decision.result()).isEqualTo("connexion refusée");
        });
    }

    @Test
    void le_cycle_ne_part_pas_si_le_budget_de_jetons_est_epuise() {
        given(tokenBudget.exceeded()).willReturn(true);
        SupervisionService service = service(properties(List.of(ORDERS), Map.of(), Map.of()));

        CycleReport report = service.runCycle("test");

        assertThat(report.processesAnalysed()).isZero();
        assertThat(report.events()).extracting(CycleEvent::label).contains("Cycle non exécuté");
        verify(agentService, never()).askStructured(anyString(), anyString(), any());
    }

    @Test
    void un_budget_epuise_degrade_l_etat_comme_une_cle_manquante() {
        given(tokenBudget.exceeded()).willReturn(true);
        given(tokenBudget.consumedToday()).willReturn(1_000L);
        given(tokenBudget.dailyLimit()).willReturn(1_000L);
        SupervisionService service = service(properties(List.of(ORDERS), Map.of(), Map.of()));

        assertThat(service.status().state()).isEqualTo(AgentState.DEGRADED);
        assertThat(service.status().stateReason()).contains("Budget de jetons");
    }

    /**
     * Cinq refus de suite, sous le seuil de pertinence par défaut (50 %) : le plancher de
     * RESTART_CONSUMER doit monter de l'incrément par défaut (5 points), une fois.
     */
    @Test
    void releve_automatiquement_le_plancher_apres_des_refus_majoritaires() {
        SupervisionService service = service(properties(List.of(ORDERS),
                Map.of(Capability.RESTART_CONSUMER, Autonomy.SUPERVISED), Map.of()));

        for (int i = 0; i < 5; i++) {
            analysisReturns(anomalyPayload("RESTART_CONSUMER", 0.90));
            service.runCycle("test");
            service.reject(service.pending().getFirst().id(), "faux positif", "opérateur");
        }

        assertThat(service.policy().confidenceThresholdOf(Capability.RESTART_CONSUMER)).isEqualTo(0.90);
        assertThat(service.policy().version()).isEqualTo("policy-v2");
        assertThat(service.audit()).anySatisfy(entry ->
                assertThat(entry.reason()).contains("plancher relevé automatiquement"));
    }

    @Test
    void ne_releve_pas_le_plancher_avec_trop_peu_de_verdicts() {
        SupervisionService service = service(properties(List.of(ORDERS),
                Map.of(Capability.RESTART_CONSUMER, Autonomy.SUPERVISED), Map.of()));

        for (int i = 0; i < 3; i++) {
            analysisReturns(anomalyPayload("RESTART_CONSUMER", 0.90));
            service.runCycle("test");
            service.reject(service.pending().getFirst().id(), "faux positif", "opérateur");
        }

        assertThat(service.policy().version()).isEqualTo("policy-v1");
    }

    @Test
    void ne_releve_pas_le_plancher_quand_la_pertinence_reste_suffisante() {
        SupervisionService service = service(properties(List.of(ORDERS),
                Map.of(Capability.NOTIFY, Autonomy.SUPERVISED), Map.of()));

        for (int i = 0; i < 4; i++) {
            analysisReturns(anomalyPayload("NOTIFY", 0.90));
            service.runCycle("test");
            service.approve(service.pending().getFirst().id(), "opérateur");
        }
        analysisReturns(anomalyPayload("NOTIFY", 0.90));
        service.runCycle("test");
        service.reject(service.pending().getFirst().id(), "faux positif", "opérateur");

        // 4 approbations sur 5 : 80 % de pertinence, largement au-dessus du seuil de 50 %.
        assertThat(service.policy().version()).isEqualTo("policy-v1");
    }

    /* ── Fenêtres de maintenance ───────────────────────────────────────── */

    @Test
    void une_fenetre_de_maintenance_tait_l_alerte_et_la_decision_sans_fausser_l_etat() {
        analysisReturns(anomalyPayload("RESTART_CONSUMER", 0.9));
        SupervisionService service = service(properties(List.of(ORDERS),
                Map.of(Capability.RESTART_CONSUMER, Autonomy.SUPERVISED), Map.of()));

        service.declareMaintenance("order-integration", Duration.ofHours(2), "Déploiement", "opérateur");
        service.runCycle("test");

        assertThat(service.alerts()).isEmpty();
        assertThat(service.decisions()).isEmpty();
        // La maintenance mute l'alerte et la décision, jamais l'observation : l'état relevé reste
        // celui que le modèle a rendu, pas un état inventé pour la circonstance.
        assertThat(service.snapshots()).singleElement()
                .extracting(ProcessSnapshot::state).isEqualTo(ProcessState.WARNING);
    }

    @Test
    void une_fenetre_de_maintenance_levee_laisse_repartir_les_decisions() {
        analysisReturns(anomalyPayload("RESTART_CONSUMER", 0.9));
        SupervisionService service = service(properties(List.of(ORDERS),
                Map.of(Capability.RESTART_CONSUMER, Autonomy.SUPERVISED), Map.of()));
        service.declareMaintenance("order-integration", Duration.ofHours(2), "Déploiement", "opérateur");

        service.endMaintenance("order-integration", "opérateur");
        service.runCycle("test");

        assertThat(service.alerts()).hasSize(1);
    }

    @Test
    void une_maintenance_sur_un_processus_inconnu_ne_se_devine_pas() {
        SupervisionService service = service(properties(List.of(ORDERS), Map.of(), Map.of()));

        assertThatThrownBy(() -> service.declareMaintenance("inexistant", Duration.ofHours(1), null, "x"))
                .isInstanceOf(UnknownProcessException.class);
    }

    @Test
    void l_apercu_liste_les_fenetres_de_maintenance_actives() {
        SupervisionService service = service(properties(List.of(ORDERS), Map.of(), Map.of()));

        service.declareMaintenance("order-integration", Duration.ofHours(1), "Déploiement", "opérateur");

        assertThat(service.overview().maintenance()).singleElement()
                .extracting(MaintenanceWindow::processId).isEqualTo("order-integration");
    }

    /* ── Incidents corrélés ────────────────────────────────────────────── */

    @Test
    void plusieurs_processus_en_anomalie_au_meme_cycle_forment_un_incident_correle() {
        MonitoredProcess billing = new MonitoredProcess("billing", "Billing", null, null, null);
        MonitoredProcess shipping = new MonitoredProcess("shipping", "Shipping", null, null, null);
        analysisReturns(Map.of("processes", List.of(), "anomalies", List.of(
                anomalyRow("order-integration", "Retard"), anomalyRow("billing", "Retard"),
                anomalyRow("shipping", "Retard"))));
        SupervisionService service = service(properties(List.of(ORDERS, billing, shipping), Map.of(), Map.of()));

        service.runCycle("test");

        assertThat(service.incidents()).singleElement().satisfies(incident -> {
            assertThat(incident.processCount()).isEqualTo(3);
            assertThat(incident.processNames())
                    .containsExactlyInAnyOrder("Order Integration", "Billing", "Shipping");
        });
    }

    @Test
    void deux_processus_en_anomalie_ne_suffisent_pas_a_un_incident_correle() {
        MonitoredProcess billing = new MonitoredProcess("billing", "Billing", null, null, null);
        analysisReturns(Map.of("processes", List.of(),
                "anomalies", List.of(anomalyRow("order-integration", "A"), anomalyRow("billing", "B"))));
        SupervisionService service = service(properties(List.of(ORDERS, billing), Map.of(), Map.of()));

        service.runCycle("test");

        assertThat(service.incidents()).isEmpty();
    }

    /* ── Simulation d'une capacité sans outil lié ─────────────────────── */

    @Test
    void une_capacite_sans_outil_lie_se_simule_plutot_que_d_echouer_quand_demande() {
        analysisReturns(anomalyPayload("RESTART_CONSUMER", 0.9));
        SupervisionProperties base = properties(List.of(ORDERS),
                Map.of(Capability.RESTART_CONSUMER, Autonomy.SUPERVISED), Map.of());
        SupervisionService service = service(simulating(base));
        service.runCycle("test");
        String id = service.pending().getFirst().id();

        Decision approved = service.approve(id, "opérateur");

        assertThat(approved.status()).isEqualTo(DecisionStatus.SIMULATED);
        assertThat(approved.result()).contains("Simulée");
    }

    /* ── Note de connaissance citée par le modèle ──────────────────────── */

    @Test
    void une_note_de_connaissance_citee_par_le_modele_se_retrouve_sur_l_alerte() {
        analysisReturns(Map.of("processes", List.of(), "anomalies", List.of(
                Map.of("processId", "order-integration", "title", "Retard", "severity", "WARNING",
                        "confidence", 0.8, "knowledgeReference", "Runbook consumer-lag-2024"))));
        SupervisionService service = service(properties(List.of(ORDERS), Map.of(), Map.of()));

        service.runCycle("test");

        assertThat(service.alerts()).singleElement()
                .extracting(Alert::knowledgeReference).isEqualTo("Runbook consumer-lag-2024");
    }

    /* ── Seuils par processus ──────────────────────────────────────────── */

    @Test
    void un_processus_avec_seuils_propres_les_recite_dans_le_prompt() {
        MonitoredProcess noisy = new MonitoredProcess("noisy", "Noisy", null, null,
                new ThresholdOverrides(5000L, null, null, null, null));
        analysisReturns(Map.of("processes", List.of(), "anomalies", List.of()));
        SupervisionService service = service(properties(List.of(noisy), Map.of(), Map.of()));

        service.runCycle("test");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(agentService).askStructured(anyString(), prompt.capture(), any());
        assertThat(prompt.getValue()).contains("seuils propres à ce processus").contains("5000");
    }

    /* ── Historique par processus ──────────────────────────────────────── */

    @Test
    void l_historique_d_un_processus_suit_son_etat_a_travers_les_cycles() {
        analysisReturns(Map.of("processes",
                List.of(Map.of("processId", "order-integration", "state", "OK")), "anomalies", List.of()));
        SupervisionService service = service(properties(List.of(ORDERS), Map.of(), Map.of()));
        service.runCycle("test");
        clock.advance(Duration.ofMinutes(5));
        analysisReturns(Map.of("processes", List.of(Map.of("processId", "order-integration",
                "state", "WARNING", "delayMillis", 90_000)), "anomalies", List.of()));
        service.runCycle("test");

        assertThat(service.processHistory("order-integration")).hasSize(2)
                .extracting(ProcessHistoryPoint::state)
                .containsExactly(ProcessState.WARNING, ProcessState.OK);
    }

    @Test
    void l_historique_d_un_processus_inconnu_ne_se_devine_pas() {
        SupervisionService service = service(properties(List.of(ORDERS), Map.of(), Map.of()));

        assertThatThrownBy(() -> service.processHistory("inexistant")).isInstanceOf(UnknownProcessException.class);
    }

    /* ── Outillage ─────────────────────────────────────────────────────── */

    private SupervisionService service(SupervisionProperties properties) {
        return service(properties, keyMissing);
    }

    private SupervisionService service(SupervisionProperties properties, ModelAvailability model) {
        given(notifier.send(any(), any())).willReturn(Optional.empty());
        return new SupervisionService(agentService, toolCatalog, properties, clock, model,
                mock(Tracer.class), CircuitBreakerRegistry.ofDefaults(),
                new InMemoryAuditRepository(properties.historySize()), notifier, tokenBudget);
    }

    private void analysisReturns(Map<String, Object> content) {
        when(agentService.askStructured(anyString(), anyString(), any()))
                .thenReturn(new AgentStructuredAnswer("cycle", content, List.of(), null, "end_turn"));
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
                autonomy, floors, actions, 200, Duration.ofMinutes(30), Duration.ofMinutes(15),
                new SupervisionProperties.Schedule(false, Duration.ofMinutes(5), Duration.ofMinutes(10)),
                new SupervisionProperties.AutoAdjust(true, 5, 0.5, 0.05),
                new SupervisionProperties.Correlation(true, 3), false);
    }

    /** Même politique, {@code simulateUnboundActions} activé — un seul cas en a besoin. */
    private static SupervisionProperties simulating(SupervisionProperties base) {
        return new SupervisionProperties(base.enabled(), base.processes(), base.mode(),
                base.confidenceThreshold(), base.thresholds(), base.autonomy(), base.confidenceThresholds(),
                base.actions(), base.historySize(), base.approvalTimeout(), base.staleAfter(),
                base.schedule(), base.autoAdjust(), base.correlation(), true);
    }

    private static Map<String, Object> anomalyRow(String processId, String title) {
        return Map.of("processId", processId, "title", title, "severity", "WARNING", "confidence", 0.8);
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
