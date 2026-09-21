// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.kex.agent.memory.LearningEntry;
import com.kex.agent.memory.LearningRepository;
import com.kex.agent.memory.MemoryIdentity;

/** No tool can approve skills. Proposals and human decisions are separate entry points. */
public final class SkillsService {
    private final LearningRepository repository;
    private final Clock clock;

    public SkillsService(LearningRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public LearningEntry propose(String owner, String title, String markdown, String evidence, String conversationId) {
        MemoryIdentity.require(owner);
        requireText(title, 200, "titre");
        requireText(markdown, 12000, "procédure Markdown");
        requireText(evidence, 4000, "preuve de réussite / provenance");
        LearningEntry entry = new LearningEntry(UUID.randomUUID().toString(), owner, "SKILL", title.strip(),
                markdown.strip(), evidence.strip(), conversationId, clock.instant(), "PENDING", null, null, null);
        repository.add(entry);
        return entry;
    }

    public List<LearningEntry> list(String owner) {
        return repository.list(MemoryIdentity.require(owner), "SKILL", Instant.EPOCH);
    }

    public List<LearningEntry> approved(String owner) {
        return list(owner).stream().filter(entry -> entry.status().equals("APPROVED")
                && entry.reviewedBy() != null && entry.reviewedAt() != null).toList();
    }

    /**
     * Les compétences approuvées dans l'ordre où elles méritent d'agir : la plus récemment approuvée
     * d'abord. Sans critère explicite, « les premières » suivait l'ordre de stockage du dépôt — une
     * compétence approuvée hier pouvait ne jamais agir au profit d'une autre approuvée l'an dernier,
     * et rien à l'écran ne le disait. Faute de télémétrie d'usage, la fraîcheur du verdict humain est
     * le seul critère qui ne soit pas inventé.
     */
    public List<LearningEntry> ranked(String owner) {
        return approved(owner).stream()
                .sorted(Comparator.comparing(LearningEntry::reviewedAt).reversed()
                        .thenComparing(LearningEntry::title))
                .toList();
    }

    /**
     * Retire une compétence approuvée de la bibliothèque. Jamais automatique : le curateur signale,
     * un humain nommé retire — même exigence que l'approbation, et pour la même raison.
     */
    public void retire(String id, String actor, String reason) {
        MemoryIdentity.require(actor);
        if (reason == null || reason.isBlank() || reason.length() > 2000) {
            throw new IllegalArgumentException("Motif de retrait requis et limité à 2000 caractères");
        }
        String owner = ownerOrFail(id, "Compétence inconnue ou non approuvée");
        if (approved(owner).stream().noneMatch(entry -> entry.id().equals(id))) {
            throw new IllegalStateException("Compétence inconnue ou non approuvée");
        }
        if (!repository.delete(owner, id)) {
            throw new IllegalStateException("Compétence inconnue ou non approuvée");
        }
    }

    /**
     * Approuve ou rejette, quel que soit le propriétaire. La revue est un geste d'administration :
     * elle exige {@code ADMIN}, là où proposer ne demande que de converser et où une compétence
     * tirée de refus appartient à l'opérateur qui a refusé. Chercher l'entrée sous l'identité de
     * l'appelant rendait donc inapprouvable tout ce qu'un non-administrateur avait proposé — la
     * proposition restait {@code PENDING} à vie, faute d'un principal à la fois propriétaire et
     * administrateur.
     *
     * <p>La compétence reste celle de son propriétaire : c'est son contexte qu'elle enrichit une
     * fois approuvée, et {@code actor} garde trace de qui a tranché.
     */
    public LearningEntry review(String id, boolean approve, String actor, String reason) {
        MemoryIdentity.require(actor);
        if (reason == null) reason = "";
        if (reason.length() > 2000) throw new IllegalArgumentException("Motif limité à 2000 caractères");
        String owner = ownerOrFail(id, "Compétence inconnue ou déjà examinée");
        if (!repository.review(owner, id, approve ? "APPROVED" : "REJECTED", actor, clock.instant(), reason)) {
            throw new IllegalStateException("Compétence inconnue ou déjà examinée");
        }
        return list(owner).stream().filter(entry -> entry.id().equals(id)).findFirst().orElseThrow();
    }

    /**
     * Tout ce qui attend une décision, tous propriétaires confondus, la plus ancienne d'abord —
     * un ordre de file d'attente, pas celui de {@link #ranked}, qui met en avant l'approuvé le
     * plus récent. Le complément de {@link #review} : approuver exige déjà {@code ADMIN} et porte
     * sur n'importe quel propriétaire, mais rien avant ceci ne permettait de *trouver* ce qu'on
     * a le droit de trancher sans en connaître l'identifiant à l'avance — il fallait le lire dans
     * l'audit, entrée par entrée.
     */
    public List<LearningEntry> pendingReviews() {
        return repository.pending("SKILL");
    }

    private String ownerOrFail(String id, String message) {
        return repository.ownerOf(id).orElseThrow(() -> new IllegalStateException(message));
    }

    private static void requireText(String value, int limit, String field) {
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new IllegalArgumentException(field + " requis et limité à " + limit + " caractères");
        }
    }
}
