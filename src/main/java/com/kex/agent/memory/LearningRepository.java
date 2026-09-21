// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LearningRepository {
    void add(LearningEntry entry);
    List<LearningEntry> list(String owner, String kind, Instant since);
    /** Atomic transition. Only a pending skill owned by this principal may change. */
    boolean review(String owner, String id, String status, String actor, Instant at, String reason);
    boolean delete(String owner, String id);

    /**
     * Le propriétaire d'une entrée, sans le connaître d'avance. Approuver exige {@code ADMIN} ;
     * proposer n'exige que de converser, et une compétence tirée de refus appartient à l'opérateur
     * qui a refusé. Chercher l'entrée sous l'identité de l'appelant rendait donc inapprouvable tout
     * ce qu'un non-administrateur avait proposé.
     *
     * <p>La transition, elle, reste atomique sur {@code (owner, id, PENDING)} : ceci ne fait que
     * dire à qui elle appartient. Une entrée supprimée entre les deux appels fait simplement
     * échouer la revue, comme une entrée inconnue.
     */
    Optional<String> ownerOf(String id);

    /**
     * Toutes les entrées {@code PENDING} d'un genre, tous propriétaires confondus. Le complément
     * de {@link #ownerOf} : {@code review} tranche une entrée quel que soit son propriétaire, mais
     * un administrateur ne pouvait la trouver qu'en lisant son identifiant dans l'audit. Sans
     * filtre de propriétaire pour la même raison que {@code review} — c'est un geste
     * d'administration, transverse par construction.
     */
    List<LearningEntry> pending(String kind);
}
