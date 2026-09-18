// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.security.Principal;
import java.util.List;
import java.util.Optional;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Le Control Center : observer, comprendre, décider, agir, vérifier. Conditionné sur la propriété
 * et non sur la présence du service, comme la base de connaissance : une condition sur bean dépend
 * de l'ordre d'enregistrement, une condition sur propriété non.
 *
 * <p>L'acteur inscrit à l'audit est le principal authentifié. Avec le seul {@code kex.agent.api-key}
 * historique, il désigne le jeton, pas une personne : tracer une identité que le système ne connaît
 * pas serait une fiction. {@code kex.agent.api-keys} nomme les jetons — chaque nom devient alors le
 * principal, donc l'acteur réellement inscrit.
 */
@RestController
@RequestMapping("/api/agent/supervision")
@ConditionalOnProperty(prefix = "kex.agent.supervision", name = "enabled", havingValue = "true",
        matchIfMissing = true)
class SupervisionController {

    private final SupervisionService supervision;
    private final WebhookNotifier notifier;

    SupervisionController(SupervisionService supervision, WebhookNotifier notifier) {
        this.supervision = supervision;
        this.notifier = notifier;
    }

    @GetMapping("/overview")
    Overview overview() {
        return supervision.overview();
    }

    @GetMapping("/status")
    AgentStatus status() {
        return supervision.status();
    }

    @GetMapping("/processes")
    List<ProcessSnapshot> processes() {
        return supervision.snapshots();
    }

    /** Dédupliquées et priorisées : deux cycles voyant le même symptôme signalent un incident. */
    @GetMapping("/alerts")
    List<Alert> alerts() {
        return supervision.alerts();
    }

    /** Mesure de l'agent lui-même : sans elle, son autonomie se règle à l'aveugle. */
    @GetMapping("/performance")
    AgentPerformance performance() {
        return supervision.performance();
    }

    @GetMapping("/cycles")
    List<CycleReport> cycles() {
        return supervision.cycles();
    }

    /** Étapes réellement atteintes par le cycle actif ; 204 quand aucun cycle ne tourne. */
    @GetMapping("/cycles/current")
    ResponseEntity<CycleProgress> currentCycle() {
        CycleProgress current = supervision.currentCycle();
        return current == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(current);
    }

    /** Déclenchement manuel : c'est le « Exécuter maintenant » de l'en-tête. */
    @PostMapping("/cycles")
    CycleReport runCycle(Principal principal) {
        return supervision.runCycle(actor(principal));
    }

    @GetMapping("/decisions")
    List<Decision> decisions() {
        return supervision.decisions();
    }

    @GetMapping("/decisions/{decision}")
    Decision decision(@PathVariable String decision) {
        return supervision.decision(decision);
    }

    @GetMapping("/decisions/pending")
    List<Decision> pending() {
        return supervision.pending();
    }

    /** Un déploiement connu n'a pas à se lire comme un incident — voir {@link MaintenanceWindow}. */
    @PostMapping("/processes/{process}/maintenance")
    MaintenanceWindow declareMaintenance(@PathVariable String process,
                                        @Valid @RequestBody MaintenanceRequest request, Principal principal) {
        return supervision.declareMaintenance(process, request.duration(), request.reason(), actor(principal));
    }

    @DeleteMapping("/processes/{process}/maintenance")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void endMaintenance(@PathVariable String process, Principal principal) {
        supervision.endMaintenance(process, actor(principal));
    }

    /** Tendance d'un processus précis à travers les derniers cycles. */
    @GetMapping("/processes/{process}/history")
    List<ProcessHistoryPoint> processHistory(@PathVariable String process) {
        return supervision.processHistory(process);
    }

    @PostMapping("/decisions/{decision}/approve")
    Decision approve(@PathVariable String decision, Principal principal) {
        return supervision.approve(decision, actor(principal));
    }

    @PostMapping("/decisions/{decision}/reject")
    Decision reject(@PathVariable String decision,
                    @RequestBody(required = false) RejectionRequest request,
                    Principal principal) {
        return supervision.reject(decision, request == null ? null : request.reason(), actor(principal));
    }

    @GetMapping("/audit")
    List<AuditEntry> audit() {
        return supervision.audit();
    }

    @GetMapping("/policy")
    SupervisionPolicy policy() {
        return supervision.policy();
    }

    @PutMapping("/policy")
    SupervisionPolicy updatePolicy(@Valid @RequestBody PolicyUpdate update, Principal principal) {
        return supervision.updatePolicy(update, actor(principal));
    }

    @PostMapping("/pause")
    AgentStatus pause(Principal principal) {
        return supervision.pause(actor(principal));
    }

    @PostMapping("/resume")
    AgentStatus resume(Principal principal) {
        return supervision.resume(actor(principal));
    }

    /**
     * Vérifie {@code kex.agent.supervision.notify.webhook-url} sans attendre qu'un cycle NOTIFY
     * réel le découvre en échec — la seule capacité dont le système cible est une personne, donc la
     * seule que rien d'autre ne peut exercer avant qu'une vraie anomalie ne s'y prête.
     */
    @PostMapping("/notify/test")
    WebhookTestResult testNotification(Principal principal) {
        String actor = actor(principal);
        Optional<String> failure = notifier.send("Test depuis Kex Agent AI",
                "Déclenché manuellement par " + actor + " pour vérifier la configuration du webhook.");
        supervision.auditAction(actor, "Test du webhook de notification",
                failure.isEmpty() ? "Envoyé" : "Échec : " + failure.get());
        return new WebhookTestResult(failure.isEmpty(), failure.orElse(null));
    }

    private static String actor(Principal principal) {
        return principal == null ? "Anonyme" : principal.getName();
    }

    /** Conflit et non erreur d'appelant : la demande est valide, c'est l'état qui la refuse. */
    @ExceptionHandler({AgentPausedException.class, CycleInProgressException.class,
            DecisionNotPendingException.class, DecisionInProgressException.class})
    ProblemDetail conflict(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(UnknownDecisionException.class)
    ProblemDetail unknownDecision(UnknownDecisionException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(UnknownProcessException.class)
    ProblemDetail unknownProcess(UnknownProcessException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }
}
