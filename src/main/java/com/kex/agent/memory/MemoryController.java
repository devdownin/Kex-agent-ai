// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.security.Principal;
import java.util.List;

import com.kex.agent.config.ActorIdentity;
import com.kex.agent.supervision.SupervisionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Un souvenir écrit à l'insu de personne, sans écran pour le voir ni le corriger, contredirait tout
 * le reste du Control Center — observer, comprendre, vérifier. Lecture seule côté modèle : aucun
 * outil n'expose la suppression, réservée à un opérateur nommé, à l'inverse de {@code remember_fact}
 * qui écrit et {@code recall_facts} qui relit.
 *
 * <p>L'audit est écrit ici, pas dans {@link MemoryService} : ce contrôleur n'entre pas dans la
 * construction du {@code ChatClient} (contrairement à {@link MemoryTools}), donc sa dépendance sur
 * {@code SupervisionService} — qui dépend en retour du {@code ChatClient} via {@code AgentService} —
 * ne ferme aucun cycle.
 */
@RestController
@RequestMapping("/api/agent/memory")
@ConditionalOnProperty(prefix = "kex.agent.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
class MemoryController {

    private final MemoryService memory;
    private final SupervisionService supervision;

    MemoryController(MemoryService memory, SupervisionService supervision) {
        this.memory = memory;
        this.supervision = supervision;
    }

    @GetMapping
    List<MemoryView> list(Principal principal) {
        return principal == null ? memory.list() : memory.list(ActorIdentity.tenantOf(principal));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void forget(@PathVariable String id, Principal principal) {
        String actor = principal == null ? "Anonyme" : principal.getName();
        MemoryEntry removed = principal == null ? memory.forget(id)
                : memory.forget(ActorIdentity.tenantOf(principal), id);
        supervision.auditAction(actor, "Souvenir supprimé", removed.content());
    }


    @ExceptionHandler(UnknownMemoryException.class)
    ProblemDetail unknown(UnknownMemoryException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }
}
