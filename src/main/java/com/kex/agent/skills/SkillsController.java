// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.security.Principal;
import java.util.List;

import com.kex.agent.config.ActorIdentity;
import com.kex.agent.memory.LearningEntry;
import com.kex.agent.supervision.SupervisionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/agent/skills")
@ConditionalOnProperty(prefix = "kex.agent.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
class SkillsController {
    private final SkillsService skills;
    private final SkillCurator curator;
    private final SupervisionService supervision;

    SkillsController(SkillsService skills, SkillCurator curator, SupervisionService supervision) {
        this.skills = skills;
        this.curator = curator;
        this.supervision = supervision;
    }

    @GetMapping
    List<LearningEntry> list(Principal principal) {
        return skills.list(tenant(principal));
    }

    /**
     * Ce qu'un administrateur a le droit de trancher, tous locataires confondus, sans en connaître
     * les identifiants à l'avance. {@code review}/{@code reject} restent volontairement transverses
     * — la boucle de refus attribue une compétence à l'opérateur qui a refusé, jamais à
     * l'administrateur qui approuve, et exiger que les deux partagent un locataire rendrait la
     * revue inapprouvable par défaut, exactement le blocage que la résolution par identifiant a
     * déjà corrigé une fois. Sans cette liste, la seule façon de trouver ce qu'on peut trancher
     * était de lire un identifiant dans l'audit, entrée par entrée.
     */
    @GetMapping("/review-queue")
    List<LearningEntry> reviewQueue() {
        return skills.pendingReviews();
    }

    /** Ce que la bibliothèque fait au prompt : ce qui agit, ce qui dort, ce qui se répète. */
    @GetMapping("/curation")
    SkillCuration curation(Principal principal) {
        return curator.curate(tenant(principal));
    }

    /** Le curateur signale, un humain nommé retire — jamais l'inverse. */
    @PostMapping("/{id}/retire")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void retire(@PathVariable String id, @Valid @RequestBody Retirement retirement, Principal principal) {
        String actor = actor(principal);
        skills.retire(id, actor, retirement.reason());
        supervision.auditAction(actor, "Compétence retirée", id + " : " + retirement.reason());
    }

    /** Imported or authored procedures always go through the same pending state. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    LearningEntry propose(@Valid @RequestBody Proposal proposal, Principal principal) {
        LearningEntry entry = skills.propose(tenant(principal), proposal.title(), proposal.markdown(),
                proposal.evidence(), proposal.conversationId());
        supervision.auditAction(actor(principal), "Compétence proposée", entry.id());
        return entry;
    }

    @PostMapping("/{id}/approve")
    LearningEntry approve(@PathVariable String id, @Valid @RequestBody Review review, Principal principal) {
        return decide(id, true, review, principal);
    }

    @PostMapping("/{id}/reject")
    LearningEntry reject(@PathVariable String id, @Valid @RequestBody Review review, Principal principal) {
        return decide(id, false, review, principal);
    }

    private LearningEntry decide(String id, boolean approve, Review review, Principal principal) {
        String actor = actor(principal);
        LearningEntry entry = skills.review(id, approve, actor, review.reason());
        supervision.auditAction(actor, approve ? "Compétence approuvée" : "Compétence rejetée",
                id + " : " + review.reason());
        return entry;
    }

    private static String actor(Principal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return principal.getName();
    }

    /**
     * Une compétence approuvée enrichit le contexte de tout le locataire : c'est lui qui la
     * possède, pas l'administrateur qui a tranché — l'audit garde qui.
     */
    private static String tenant(Principal principal) {
        actor(principal);
        return ActorIdentity.tenantOf(principal);
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    record Proposal(@NotBlank @Size(max = 200) String title,
                    @NotBlank @Size(max = 12000) String markdown,
                    @NotBlank @Size(max = 4000) String evidence,
                    @Size(max = 255) String conversationId) { }
    record Review(@Size(max = 2000) String reason) { }
    /** Motif obligatoire, contrairement à une revue : un retrait sans raison est irrelisable. */
    record Retirement(@NotBlank @Size(max = 2000) String reason) { }
}
