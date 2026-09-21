// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.time.Instant;
import java.util.List;

/**
 * État de la bibliothèque de compétences approuvées : ce qui agit réellement, et ce qui dort. Un
 * rapport, jamais une action — le retrait passe par un humain nommé, comme l'approbation.
 *
 * @param approved    compétences approuvées au total
 * @param injected    celles qui entrent réellement dans le contexte, les plus récemment approuvées
 * @param dormant     approuvées mais au-delà du plafond d'injection : elles n'agissent pas
 * @param stale       approuvées depuis plus longtemps que la fenêtre de fraîcheur configurée
 * @param duplicates  groupes de compétences au même titre normalisé, candidates à une consolidation
 * @param characters  caractères que les compétences injectées pèsent dans le prompt
 */
public record SkillCuration(int approved, List<Digest> injected, List<Digest> dormant,
                            List<Digest> stale, List<Duplicate> duplicates, int characters) {

    public SkillCuration {
        injected = List.copyOf(injected);
        dormant = List.copyOf(dormant);
        stale = List.copyOf(stale);
        duplicates = List.copyOf(duplicates);
    }

    public record Digest(String id, String title, Instant reviewedAt, String reviewedBy, int characters) { }

    public record Duplicate(String title, List<Digest> entries) {
        public Duplicate {
            entries = List.copyOf(entries);
        }
    }
}
