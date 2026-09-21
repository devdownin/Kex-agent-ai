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
    public void retire(String owner, String id, String actor, String reason) {
        MemoryIdentity.require(owner);
        MemoryIdentity.require(actor);
        if (reason == null || reason.isBlank() || reason.length() > 2000) {
            throw new IllegalArgumentException("Motif de retrait requis et limité à 2000 caractères");
        }
        if (approved(owner).stream().noneMatch(entry -> entry.id().equals(id))) {
            throw new IllegalStateException("Compétence inconnue ou non approuvée");
        }
        if (!repository.delete(owner, id)) {
            throw new IllegalStateException("Compétence inconnue ou non approuvée");
        }
    }

    public LearningEntry review(String owner, String id, boolean approve, String actor, String reason) {
        MemoryIdentity.require(owner);
        MemoryIdentity.require(actor);
        if (reason == null) reason = "";
        if (reason.length() > 2000) throw new IllegalArgumentException("Motif limité à 2000 caractères");
        if (!repository.review(owner, id, approve ? "APPROVED" : "REJECTED", actor, clock.instant(), reason)) {
            throw new IllegalStateException("Compétence inconnue ou déjà examinée");
        }
        return list(owner).stream().filter(entry -> entry.id().equals(id)).findFirst().orElseThrow();
    }

    private static void requireText(String value, int limit, String field) {
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new IllegalArgumentException(field + " requis et limité à " + limit + " caractères");
        }
    }
}
