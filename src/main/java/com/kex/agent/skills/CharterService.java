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

/**
 * Charte d'exploitation : les consignes durables que l'exploitant veut voir rappelées à chaque
 * échange, sans redéployer. Le prompt système, lui, reste figé au démarrage — c'est ce qui le rend
 * opposable : une charte qui pourrait le réécrire permettrait à qui la modifie d'annuler la
 * gouvernance qu'elle est censée servir.
 *
 * <p>D'où la règle, identique à celle du contexte durable : la charte est une donnée de référence.
 * Elle peut restreindre ce que l'agent propose, jamais élargir une autonomie, une permission ou une
 * approbation — le même sens unique que le mode d'exécution face à l'autonomie d'une capacité.
 *
 * <p>Chaque version est écrite plutôt que remplacée : une charte relue six mois plus tard sans son
 * motif ni son auteur est aussi inexplicable qu'une décision sans sa version de politique.
 */
public final class CharterService {
    static final String KIND = "CHARTER";
    private static final int MAX_CHARACTERS = 8000;

    private final LearningRepository repository;
    private final Clock clock;

    public CharterService(LearningRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Charter current(String owner) {
        return versions(owner).stream().findFirst()
                .map(entry -> new Charter(entry.markdown(), entry.reviewedBy(), entry.reviewedAt(),
                        entry.reviewReason()))
                .orElse(Charter.NONE);
    }

    /** Historique complet, du plus récent au plus ancien. */
    public List<LearningEntry> versions(String owner) {
        return repository.list(MemoryIdentity.require(owner), KIND, Instant.EPOCH).stream()
                .sorted(Comparator.comparing(LearningEntry::createdAt).reversed())
                .toList();
    }

    public Charter update(String owner, String markdown, String actor, String reason) {
        MemoryIdentity.require(owner);
        MemoryIdentity.require(actor);
        if (markdown == null || markdown.length() > MAX_CHARACTERS) {
            throw new IllegalArgumentException("Charte limitée à " + MAX_CHARACTERS + " caractères");
        }
        if (reason == null || reason.isBlank() || reason.length() > 2000) {
            throw new IllegalArgumentException("Motif requis et limité à 2000 caractères");
        }
        Instant now = clock.instant();
        // Le statut est APPROVED dès l'écriture : contrairement à une compétence, qui naît d'une
        // proposition du modèle, la charte n'a pas d'autre auteur qu'un humain nommé — la faire
        // passer par une revue reviendrait à se faire approuver par soi-même.
        LearningEntry entry = new LearningEntry(UUID.randomUUID().toString(), owner, KIND,
                "Charte d'exploitation", markdown.strip(), "Écrite par " + actor, null, now,
                "APPROVED", actor, now, reason.strip());
        repository.add(entry);
        return new Charter(entry.markdown(), actor, now, entry.reviewReason());
    }
}
