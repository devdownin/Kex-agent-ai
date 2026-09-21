// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kex.agent.memory.LearningEntry;
import com.kex.agent.memory.MemoryProperties;

/**
 * Regarde ce que la bibliothèque de compétences fait réellement au prompt. Chaque échange réussi en
 * propose une nouvelle, et rien ne les retirait jamais : la bibliothèque ne faisait que grandir
 * pendant que seules les premières agissaient, sans qu'un écran le dise.
 *
 * <p>Il n'agit pas de lui-même, et ce n'est pas une prudence de façade : retirer une compétence
 * change le comportement de toutes les conversations suivantes du même propriétaire, exactement ce
 * qu'une approbation humaine nommée existe pour trancher. Il n'est pas non plus branché sur une
 * minuterie — en multi-instance, chaque réplique rendrait le même verdict et notifierait en double,
 * le piège déjà documenté pour le cycle de supervision.
 *
 * <p>Aucune note inventée : sans télémétrie d'usage, personne ne sait laquelle a servi. Ce rapport
 * ne dit que ce qui est mesurable — laquelle agit, laquelle dort, laquelle en double une autre,
 * laquelle n'a pas été revue depuis longtemps.
 */
public final class SkillCurator {
    private final SkillsService skills;
    private final MemoryProperties properties;
    private final Clock clock;

    public SkillCurator(SkillsService skills, MemoryProperties properties, Clock clock) {
        this.skills = skills;
        this.properties = properties;
        this.clock = clock;
    }

    public SkillCuration curate(String owner) {
        List<LearningEntry> ranked = skills.ranked(owner);
        int limit = properties.skills().injected();
        List<SkillCuration.Digest> injected = digests(ranked.stream().limit(limit).toList());
        List<SkillCuration.Digest> dormant = digests(ranked.stream().skip(limit).toList());
        Instant cutoff = clock.instant().minus(properties.skills().staleAfter());
        List<SkillCuration.Digest> stale = digests(ranked.stream()
                .filter(entry -> entry.reviewedAt().isBefore(cutoff)).toList());
        int characters = injected.stream().mapToInt(SkillCuration.Digest::characters).sum();
        return new SkillCuration(ranked.size(), injected, dormant, stale, duplicates(ranked), characters);
    }

    /**
     * Deux compétences au même titre normalisé se lisent comme une seule à l'écran, et pèsent deux
     * fois dans le prompt. La comparaison reste sur le titre : rapprocher deux procédures sur leur
     * contenu demanderait un jugement, donc un appel au modèle, donc un verdict non reproductible.
     */
    private List<SkillCuration.Duplicate> duplicates(List<LearningEntry> ranked) {
        Map<String, List<LearningEntry>> byTitle = new LinkedHashMap<>();
        for (LearningEntry entry : ranked) {
            byTitle.computeIfAbsent(normalize(entry.title()), key -> new ArrayList<>()).add(entry);
        }
        return byTitle.values().stream()
                .filter(group -> group.size() > 1)
                .map(group -> new SkillCuration.Duplicate(group.getFirst().title(), digests(group)))
                .toList();
    }

    private List<SkillCuration.Digest> digests(List<LearningEntry> entries) {
        return entries.stream()
                .map(entry -> new SkillCuration.Digest(entry.id(), entry.title(), entry.reviewedAt(),
                        entry.reviewedBy(),
                        Math.min(entry.markdown().length(), properties.skills().charactersPerSkill())))
                .toList();
    }

    private static String normalize(String title) {
        return title.strip().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }
}
