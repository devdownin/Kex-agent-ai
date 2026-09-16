// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.util.StringUtils;

/**
 * La garde d'écriture tient ici, pas dans le prompt : le modèle choisit d'appeler l'outil, ce
 * service décide combien en rester (capacité, longueur, rétention). La compter sur la seule bonne
 * volonté du prompt système laisserait la panne la plus citée sur ce sujet — pas de garde, tout
 * devient permanent — dépendre d'un modèle qui change.
 */
class MemoryService {

    private final MemoryRepository repository;
    private final MemoryProperties properties;
    private final Clock clock;

    MemoryService(MemoryRepository repository, MemoryProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param replaces identifiant du souvenir devenu faux, {@code null} pour un fait nouveau. Sans
     *                 ce remplacement explicite, une correction s'ajouterait à côté du fait qu'elle
     *                 contredit et les deux seraient relus ensemble.
     */
    String remember(String content, String replaces, String conversationId) {
        String trimmed = content == null ? "" : content.strip();
        if (trimmed.isEmpty()) {
            return "Rien à retenir : contenu vide.";
        }
        String bounded = trimmed.length() > properties.maxContentLength()
                ? trimmed.substring(0, properties.maxContentLength())
                : trimmed;
        String id = UUID.randomUUID().toString();
        repository.add(new MemoryEntry(id, bounded, conversationId, clock.instant(), null));

        if (!StringUtils.hasText(replaces)) {
            return "Retenu.";
        }
        return repository.supersede(replaces.strip(), id)
                ? "Retenu, et le souvenir remplacé est marqué périmé."
                : "Retenu, mais aucun souvenir valable ne porte cet identifiant : rien n'a été marqué périmé.";
    }

    List<MemoryFact> recall() {
        Instant since = clock.instant().minus(properties.retention());
        return repository.active(since).stream()
                .map(entry -> new MemoryFact(entry.id(), entry.content()))
                .toList();
    }
}
