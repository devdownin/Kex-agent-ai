// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import com.kex.agent.agent.AgentService;
import com.kex.agent.agent.AgentStructuredAnswer;
import com.kex.agent.mcp.McpToolCatalog;
import com.kex.agent.mcp.McpToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Le moteur de supervision : un cycle observe, fait analyser les signaux par le modèle, puis
 * transforme chaque anomalie en décision dont la politique dit si elle s'exécute, attend un humain,
 * ou reste une simple recommandation.
 *
 * <p>Le cycle ne part que sur demande explicite. Pas de planificateur : en multi-instance, chaque
 * réplique lancerait le sien et les actions partiraient en double. Ajouter l'ordonnancement suppose
 * un verrou partagé, donc une base — une décision d'exploitation qui n'a pas à être prise ici.
 */
@Service
public class SupervisionService {

    private static final Logger log = LoggerFactory.getLogger(SupervisionService.class);

    private static final String AGENT = "Agent";

    private final AgentService agentService;
    private final McpToolCatalog toolCatalog;
    private final SupervisionProperties properties;
    private final Clock clock;

    private final AtomicReference<SupervisionPolicy> policy = new AtomicReference<>();
    private final AtomicInteger policyRevision = new AtomicInteger(1);

    /**
     * Un seul cycle à la fois. Refuser plutôt que mettre en file : deux analyses simultanées
     * décideraient deux fois sur les mêmes faits, donc exécuteraient deux fois la même action.
     */
    private final ReentrantLock cycleLock = new ReentrantLock();

    private final History<CycleReport> cycles;
    private final History<Anomaly> anomalies;
    private final History<Decision> decisions;
    private final History<AuditEntry> audit;
    private final Map<String, Decision> decisionsById = new ConcurrentHashMap<>();

    private volatile List<ProcessSnapshot> snapshots = List.of();
    private volatile boolean paused;

    SupervisionService(AgentService agentService, McpToolCatalog toolCatalog,
                       SupervisionProperties properties, Clock clock) {
        this.agentService = agentService;
        this.toolCatalog = toolCatalog;
        this.properties = properties;
        this.clock = clock;
        this.cycles = new History<>(properties.historySize());
        this.anomalies = new History<>(properties.historySize());
        this.decisions = new History<>(properties.historySize());
        this.audit = new History<>(properties.historySize());
        this.policy.set(new SupervisionPolicy("policy-v1", properties.mode(),
                Map.copyOf(properties.autonomy()), properties.confidenceThreshold(), properties.thresholds()));
        this.snapshots = properties.processes().stream()
                .map(process -> new ProcessSnapshot(process.id(), process.name(), ProcessState.UNKNOWN,
                        null, null, null, "Aucune analyse exécutée"))
                .toList();
    }

    /* ── Lectures ──────────────────────────────────────────────────────── */

    public SupervisionPolicy policy() {
        return policy.get();
    }

    public List<MonitoredProcess> processes() {
        return properties.processes();
    }

    public List<ProcessSnapshot> snapshots() {
        return snapshots;
    }

    public List<Anomaly> anomalies() {
        return anomalies.list();
    }

    public List<CycleReport> cycles() {
        return cycles.list();
    }

    public List<AuditEntry> audit() {
        return audit.list();
    }

    public List<Decision> decisions() {
        expireStalePendings();
        return decisions.list();
    }

    public Decision decision(String id) {
        expireStalePendings();
        Decision decision = decisionsById.get(id);
        if (decision == null) {
            throw new UnknownDecisionException(id);
        }
        return decision;
    }

    public List<Decision> pending() {
        return decisions().stream().filter(d -> d.status() == DecisionStatus.PENDING_APPROVAL).toList();
    }

    public AgentStatus status() {
        CycleReport last = cycles.first();
        Instant lastAt = last == null ? null : last.finishedAt();
        Instant staleSince = lastAt != null && clock.instant().isAfter(lastAt.plus(properties.staleAfter()))
                ? lastAt
                : null;
        SupervisionPolicy current = policy.get();
        return new AgentStatus(state(last, staleSince), current.mode(), paused, cycleLock.isLocked(),
                lastAt, last == null ? null : last.id(), staleSince, current.version(),
                current.confidenceThreshold());
    }

    private AgentState state(CycleReport last, Instant staleSince) {
        if (paused) {
            return AgentState.PAUSED;
        }
        if (cycleLock.isLocked()) {
            return AgentState.ANALYSING;
        }
        if (last != null && last.failure() != null) {
            return AgentState.ERROR;
        }
        // Dégradé, pas opérationnel : des données périmées ou un processus dont on ne sait rien
        // ressemblent à un système sain sur un tableau de bord, et c'est exactement le piège.
        boolean blind = staleSince != null
                || snapshots.stream().anyMatch(snapshot -> snapshot.state() == ProcessState.UNKNOWN);
        return blind ? AgentState.DEGRADED : AgentState.OPERATIONAL;
    }

    public Overview overview() {
        List<ProcessSnapshot> current = snapshots;
        Map<ProcessState, Long> counts = new EnumMap<>(ProcessState.class);
        current.forEach(snapshot -> counts.merge(snapshot.state(), 1L, Long::sum));
        CycleReport last = cycles.first();
        List<Anomaly> open = last == null ? List.of()
                : anomalies.list().stream().filter(a -> last.id().equals(a.cycleId())).toList();
        return new Overview(status(), current.size(),
                count(counts, ProcessState.OK), count(counts, ProcessState.WARNING),
                count(counts, ProcessState.ERROR), count(counts, ProcessState.UNKNOWN),
                open.size(), pending().size(), current, open, pending(), last);
    }

    private static int count(Map<ProcessState, Long> counts, ProcessState state) {
        return counts.getOrDefault(state, 0L).intValue();
    }

    /* ── Pilotage ──────────────────────────────────────────────────────── */

    public AgentStatus pause(String actor) {
        paused = true;
        record(actor, "Agent mis en pause", null, null, null, "Aucune analyse ne sera exécutée");
        return status();
    }

    public AgentStatus resume(String actor) {
        paused = false;
        record(actor, "Agent repris", null, null, null, "Les analyses peuvent repartir");
        return status();
    }

    public SupervisionPolicy updatePolicy(PolicyUpdate update, String actor) {
        SupervisionPolicy updated = policy.updateAndGet(current -> new SupervisionPolicy(
                "policy-v" + policyRevision.incrementAndGet(),
                update.mode() == null ? current.mode() : update.mode(),
                update.autonomy() == null ? current.autonomy() : Map.copyOf(update.autonomy()),
                update.confidenceThreshold() == null ? current.confidenceThreshold() : update.confidenceThreshold(),
                update.thresholds() == null ? current.thresholds() : update.thresholds()));
        record(actor, "Politique modifiée", null, null, update.reason(),
                "Nouvelle version : " + updated.version());
        return updated;
    }

    /* ── Cycle ─────────────────────────────────────────────────────────── */

    public CycleReport runCycle(String actor) {
        if (paused) {
            throw new AgentPausedException();
        }
        if (!cycleLock.tryLock()) {
            throw new CycleInProgressException();
        }
        try {
            return cycle(actor);
        }
        finally {
            cycleLock.unlock();
        }
    }

    private CycleReport cycle(String actor) {
        String cycleId = UUID.randomUUID().toString();
        Instant started = clock.instant();
        List<CycleEvent> events = new ArrayList<>();
        events.add(new CycleEvent(started, "Analyse démarrée", "Déclenchée par " + actor));

        List<MonitoredProcess> monitored = properties.processes();
        if (monitored.isEmpty()) {
            // État vide honnête : rien n'a été déclaré, donc rien n'est surveillé. Inventer des
            // processus pour remplir l'écran serait pire qu'un écran vide.
            events.add(new CycleEvent(clock.instant(), "Aucun processus déclaré",
                    "kex.agent.supervision.processes est vide"));
            return finish(cycleId, started, events, 0, 0, List.of(), null);
        }

        events.add(new CycleEvent(clock.instant(), "Interrogation des outils",
                monitored.size() + " processus à relever"));

        AgentStructuredAnswer answer;
        // La conversation du cycle est jetable : réutiliser un identifiant ferait grossir la
        // mémoire à chaque exécution jusqu'au plafond, en payant du contexte pour des faits périmés.
        String conversationId = "supervision-" + cycleId;
        try {
            answer = agentService.askStructured(conversationId, prompt(monitored), CycleAnalysis.schema());
        }
        catch (RuntimeException ex) {
            log.warn("Cycle de supervision {} interrompu", cycleId, ex);
            events.add(new CycleEvent(clock.instant(), "Analyse interrompue", ex.getMessage()));
            record(AGENT, "Cycle interrompu", null, null, ex.getMessage(), "Échec");
            return finish(cycleId, started, events, 0, 0, List.of(), ex.getMessage());
        }
        finally {
            agentService.clear(conversationId);
        }

        Instant at = clock.instant();
        snapshots = CycleAnalysis.snapshots(answer.content(), monitored);
        List<Anomaly> detected = CycleAnalysis.anomalies(answer.content(), monitored, cycleId, at);
        detected.forEach(anomalies::add);

        events.add(new CycleEvent(at, monitored.size() + " processus analysés",
                answer.tools().size() + " appels d'outils"));
        events.add(new CycleEvent(at, detected.size() + " anomalies détectées",
                detected.stream().map(Anomaly::title).reduce((a, b) -> a + " · " + b).orElse("Aucune")));

        List<Decision> taken = detected.stream().map(anomaly -> decide(anomaly, cycleId)).toList();
        long pendingCount = taken.stream().filter(d -> d.status() == DecisionStatus.PENDING_APPROVAL).count();
        events.add(new CycleEvent(clock.instant(), taken.size() + " décisions prises",
                pendingCount + " en attente de validation"));

        return finish(cycleId, started, events, monitored.size(), detected.size(), taken, null);
    }

    private CycleReport finish(String cycleId, Instant started, List<CycleEvent> events, int analysed,
                               int detected, List<Decision> taken, String failure) {
        Instant finished = clock.instant();
        events.add(new CycleEvent(finished, "Cycle terminé", failure == null ? "Sans erreur" : failure));
        int pendingCount = (int) taken.stream().filter(d -> d.status() == DecisionStatus.PENDING_APPROVAL).count();
        CycleReport report = new CycleReport(cycleId, started, finished, analysed, detected, taken.size(),
                pendingCount, List.copyOf(events), failure);
        cycles.add(report);
        return report;
    }

    private String prompt(List<MonitoredProcess> monitored) {
        Thresholds thresholds = policy.get().thresholds();
        StringBuilder prompt = new StringBuilder("""
                Tu supervises des processus d'intégration. Relève leur état en interrogeant les \
                outils dont tu disposes — n'invente aucune valeur : un relevé impossible se rend \
                avec l'état UNKNOWN.

                Processus à relever :
                """);
        for (MonitoredProcess process : monitored) {
            prompt.append("- %s (identifiant %s)".formatted(process.name(), process.id()));
            if (StringUtils.hasText(process.description())) {
                prompt.append(" — ").append(process.description());
            }
            if (StringUtils.hasText(process.hint())) {
                prompt.append(" — où regarder : ").append(process.hint());
            }
            prompt.append('\n');
        }
        prompt.append("""

                Signale une anomalie quand un de ces seuils est franchi, et cite la mesure qui le \
                montre dans les observations :
                - retard de consommation (consumer lag) supérieur à %d
                - taux d'erreur supérieur à %.1f %%
                - temps de traitement supérieur à %s
                - messages bloqués au-delà de %d
                Juge les tendances sur une fenêtre de %s.
                """.formatted(thresholds.consumerLag(), thresholds.errorRatePercent(),
                thresholds.processingTime(), thresholds.blockedMessages(), thresholds.observationWindow()));
        return prompt.toString();
    }

    /* ── Décision ──────────────────────────────────────────────────────── */

    private Decision decide(Anomaly anomaly, String cycleId) {
        SupervisionPolicy current = policy.get();
        // Sans capacité recommandée, il reste toujours celle de prévenir quelqu'un.
        Capability capability = anomaly.capability() == null ? Capability.NOTIFY : anomaly.capability();
        Autonomy autonomy = current.effectiveAutonomy(capability);
        Instant at = clock.instant();

        Decision decision = new Decision(UUID.randomUUID().toString(), cycleId, anomaly.id(),
                anomaly.processId(), anomaly.processName(), capability,
                "Ramener %s sous les seuils de surveillance".formatted(anomaly.processName()),
                anomaly.analysis(), action(capability, anomaly), anomaly.observations(),
                impact(capability), anomaly.confidence(), DecisionStatus.PENDING_APPROVAL, null,
                current.version(), anomaly.id(), at, null, at.plus(properties.approvalTimeout()));

        if (autonomy == Autonomy.FORBIDDEN) {
            decision = decision.resolvedAs(DecisionStatus.BLOCKED,
                    "Capacité %s interdite par la politique : recommandation seule".formatted(capability), at);
        }
        else if (autonomy == Autonomy.AUTOMATIC && anomaly.confidence() >= current.confidenceThreshold()) {
            decision = execute(decision, AGENT);
        }
        else if (autonomy == Autonomy.AUTOMATIC) {
            // Autonome mais pas assez sûr : la validation humaine est le repli, pas l'abandon.
            decision = new Decision(decision.id(), decision.cycleId(), decision.anomalyId(),
                    decision.processId(), decision.processName(), decision.capability(), decision.objective(),
                    decision.context(), decision.action(), decision.observations(), decision.estimatedImpact(),
                    decision.confidence(), DecisionStatus.PENDING_APPROVAL,
                    "Confiance %.0f %% sous le seuil de %.0f %%".formatted(anomaly.confidence() * 100,
                            current.confidenceThreshold() * 100),
                    decision.policyVersion(), decision.correlationId(), decision.decidedAt(), null,
                    decision.expiresAt());
        }

        store(decision);
        record(AGENT, decision.action(), decision.processId(), decision.id(), anomaly.title(),
                describe(decision));
        return decision;
    }

    public Decision approve(String id, String actor) {
        Decision decision = pendingOrFail(id);
        Decision executed = execute(decision, actor);
        store(executed);
        record(actor, "Validation : " + decision.action(), decision.processId(), id,
                "Approuvée par " + actor, describe(executed));
        return executed;
    }

    public Decision reject(String id, String reason, String actor) {
        Decision decision = pendingOrFail(id);
        Decision rejected = decision.resolvedAs(DecisionStatus.REJECTED,
                StringUtils.hasText(reason) ? reason : "Refusée par " + actor, clock.instant());
        store(rejected);
        record(actor, "Refus : " + decision.action(), decision.processId(), id, reason, "Refusée");
        return rejected;
    }

    private Decision pendingOrFail(String id) {
        expireStalePendings();
        Decision decision = decisionsById.get(id);
        if (decision == null) {
            throw new UnknownDecisionException(id);
        }
        if (decision.status() != DecisionStatus.PENDING_APPROVAL) {
            throw new DecisionNotPendingException(id, decision.status());
        }
        return decision;
    }

    /**
     * Une demande de validation périmée n'est pas exécutable : approuvée trois heures après les
     * faits, elle agirait sur une situation qui n'existe plus.
     */
    private void expireStalePendings() {
        Instant now = clock.instant();
        List<Decision> expired = decisionsById.values().stream()
                .filter(d -> d.status() == DecisionStatus.PENDING_APPROVAL)
                .filter(d -> d.expiresAt() != null && now.isAfter(d.expiresAt()))
                .toList();
        for (Decision decision : expired) {
            store(decision.resolvedAs(DecisionStatus.FAILED, "Demande de validation expirée", now));
            record("Système", "Expiration : " + decision.action(), decision.processId(), decision.id(),
                    "Aucune réponse avant " + decision.expiresAt(), "Expirée");
        }
    }

    private Decision execute(Decision decision, String actor) {
        ActionBinding binding = properties.actions().get(decision.capability());
        Instant at = clock.instant();
        if (binding == null || !StringUtils.hasText(binding.connection()) || !StringUtils.hasText(binding.tool())) {
            // Le droit d'agir et le moyen d'agir sont deux choses : la politique peut autoriser une
            // capacité qu'aucun outil n'implémente, et le dire vaut mieux que l'exécuter à moitié.
            return decision.resolvedAs(DecisionStatus.FAILED,
                    "Aucun outil MCP lié à la capacité " + decision.capability(), at);
        }
        Map<String, Object> arguments = new HashMap<>(binding.arguments());
        arguments.put("processId", decision.processId());
        arguments.put("decisionId", decision.id());
        try {
            McpToolResult result = toolCatalog.call(binding.connection(), binding.tool(), arguments);
            String output = String.join("\n", result.content());
            return decision.resolvedAs(result.error() ? DecisionStatus.FAILED : DecisionStatus.EXECUTED,
                    StringUtils.hasText(output) ? output : "Exécutée par " + actor, at);
        }
        catch (RuntimeException ex) {
            log.warn("Exécution de la décision {} en échec", decision.id(), ex);
            return decision.resolvedAs(DecisionStatus.FAILED, ex.getMessage(), at);
        }
    }

    private static String action(Capability capability, Anomaly anomaly) {
        return StringUtils.hasText(anomaly.recommendation())
                ? anomaly.recommendation()
                : switch (capability) {
                    case NOTIFY -> "Notifier l'équipe d'exploitation";
                    case CREATE_INCIDENT -> "Créer un incident";
                    case RESTART_CONSUMER -> "Redémarrer le consumer";
                    case REPLAY_MESSAGES -> "Rejouer les messages en attente";
                    case MODIFY_CONFIGURATION -> "Modifier la configuration";
                };
    }

    /** Impact estimé par capacité : l'interface doit l'afficher avant toute validation humaine. */
    private static String impact(Capability capability) {
        return switch (capability) {
            case NOTIFY, CREATE_INCIDENT -> "Nul sur le système surveillé";
            case RESTART_CONSUMER -> "Faible : interruption de quelques secondes de la consommation";
            case REPLAY_MESSAGES -> "Modéré : des messages peuvent être traités deux fois";
            case MODIFY_CONFIGURATION -> "Élevé : effet durable sur le système surveillé";
        };
    }

    private static String describe(Decision decision) {
        return decision.status() + (StringUtils.hasText(decision.result()) ? " — " + decision.result() : "");
    }

    private void store(Decision decision) {
        Decision previous = decisionsById.put(decision.id(), decision);
        if (previous == null) {
            Decision evicted = decisions.add(decision);
            if (evicted != null) {
                decisionsById.remove(evicted.id());
            }
        }
        else {
            decisions.replace(entry -> entry.id().equals(decision.id()) ? decision : entry);
        }
    }

    private void record(String actor, String action, String processId, String decisionId, String reason,
                        String result) {
        audit.add(new AuditEntry(UUID.randomUUID().toString(), clock.instant(), actor, action, processId,
                decisionId, reason, policy.get().version(), result,
                decisionId == null ? UUID.randomUUID().toString() : decisionId));
    }
}
