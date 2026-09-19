// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.time.Clock;
import java.time.Instant;
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
