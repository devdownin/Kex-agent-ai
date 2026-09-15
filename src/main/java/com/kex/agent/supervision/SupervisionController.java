// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.security.Principal;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Le Control Center : observer, comprendre, décider, agir, vérifier. Conditionné sur la propriété
 * et non sur la présence du service, comme la base de connaissance : une condition sur bean dépend
 * de l'ordre d'enregistrement, une condition sur propriété non.
 *
 * <p>L'acteur inscrit à l'audit est le principal authentifié. Le bearer étant unique et partagé,
 * il désigne le jeton, pas une personne : tracer une identité que le système ne connaît pas serait
 * une fiction, et l'audit n'en vaudrait rien.
 */
@RestController
@RequestMapping("/api/agent/supervision")
@ConditionalOnProperty(prefix = "kex.agent.supervision", name = "enabled", havingValue = "true",
        matchIfMissing = true)
class SupervisionController {

    private final SupervisionService supervision;

    SupervisionController(SupervisionService supervision) {
        this.supervision = supervision;
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

    @GetMapping("/anomalies")
    List<Anomaly> anomalies() {
        return supervision.anomalies();
    }

    @GetMapping("/cycles")
    List<CycleReport> cycles() {
        return supervision.cycles();
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

    private static String actor(Principal principal) {
        return principal == null ? "Anonyme" : principal.getName();
    }

    /** Conflit et non erreur d'appelant : la demande est valide, c'est l'état qui la refuse. */
    @ExceptionHandler({AgentPausedException.class, CycleInProgressException.class,
            DecisionNotPendingException.class})
    ProblemDetail conflict(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(UnknownDecisionException.class)
    ProblemDetail unknownDecision(UnknownDecisionException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }
}
