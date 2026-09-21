// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.memory.FileLearningRepository;
import com.kex.agent.memory.LearningEntry;
import com.kex.agent.memory.LearningRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Qui approuve n'est pas qui propose. La revue exige {@code ADMIN} ; proposer n'exige rien de plus
 * que de converser, et la boucle d'apprentissage sur les refus attribue la compétence à l'opérateur
 * dont les refus l'ont provoquée — un rôle {@code OPERATOR}, jamais {@code ADMIN}.
 *
 * <p>Tant que la revue cherchait l'entrée sous l'identité de l'appelant, ces compétences-là étaient
 * donc inapprouvables : il aurait fallu être à la fois leur propriétaire et administrateur. Elles
 * restaient {@code PENDING} à vie, et la boucle ne pouvait jamais se refermer.
 */
class SkillReviewOwnershipTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void un_administrateur_approuve_la_competence_d_un_autre_proprietaire(@TempDir Path directory) {
        SkillsService skills = skills(directory);
        LearningEntry proposed = skills.propose("ops-console", "Ne pas redémarrer en journée",
                "# Règle tirée de refus humains", "Trois refus concordants", null);

        LearningEntry reviewed = skills.review(proposed.id(), true, "admin", "règle juste");

        assertThat(reviewed.status()).isEqualTo("APPROVED");
        assertThat(reviewed.reviewedBy()).isEqualTo("admin");
        // La compétence reste celle de son propriétaire : c'est son contexte qu'elle enrichit.
        assertThat(reviewed.owner()).isEqualTo("ops-console");
        assertThat(skills.approved("ops-console")).hasSize(1);
        assertThat(skills.approved("admin")).isEmpty();
    }

    @Test
    void un_administrateur_retire_la_competence_d_un_autre_proprietaire(@TempDir Path directory) {
        SkillsService skills = skills(directory);
        LearningEntry proposed = skills.propose("ops-console", "Règle obsolète", "# procédure", "preuve", null);
        skills.review(proposed.id(), true, "admin", "ok");

        skills.retire(proposed.id(), "admin", "le batch de nuit a changé");

        assertThat(skills.approved("ops-console")).isEmpty();
    }

    @Test
    void une_competence_inconnue_reste_un_conflit_et_non_une_erreur_de_propriete(@TempDir Path directory) {
        SkillsService skills = skills(directory);

        assertThatThrownBy(() -> skills.review("inexistante", true, "admin", "ok"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> skills.retire("inexistante", "admin", "obsolète"))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Une compétence déjà tranchée ne se re-tranche pas, quel que soit l'administrateur. */
    @Test
    void une_competence_deja_examinee_refuse_une_seconde_revue(@TempDir Path directory) {
        SkillsService skills = skills(directory);
        LearningEntry proposed = skills.propose("ops-console", "Règle", "# procédure", "preuve", null);
        skills.review(proposed.id(), false, "admin", "non");

        assertThatThrownBy(() -> skills.review(proposed.id(), true, "autre-admin", "si"))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Le complément qui manquait à cette PR-ci : un administrateur peut trancher n'importe quel
     * propriétaire, mais ne pouvait *trouver* une compétence en attente qu'en lisant son
     * identifiant dans l'audit. La file de revue rend transverse la découverte, comme la revue
     * elle-même l'est déjà — et se vide au fur et à mesure des décisions.
     */
    @Test
    void la_file_de_revue_traverse_les_proprietaires_et_se_vide_a_la_decision(@TempDir Path directory) {
        SkillsService skills = skills(directory);
        LearningEntry deOpsConsole = skills.propose("ops-console", "Ne pas redémarrer en journée",
                "# procédure", "preuve", null);
        skills.propose("ci-pipeline", "Ne pas rejouer un sujet en pointe", "# procédure", "preuve", null);

        assertThat(skills.pendingReviews()).extracting(LearningEntry::owner)
                .containsExactlyInAnyOrder("ops-console", "ci-pipeline");

        skills.review(deOpsConsole.id(), true, "admin", "règle juste");

        assertThat(skills.pendingReviews()).extracting(LearningEntry::owner)
                .containsExactly("ci-pipeline");
    }

    private SkillsService skills(Path directory) {
        LearningRepository repository = new FileLearningRepository(new ObjectMapper().findAndRegisterModules(),
                directory.resolve("learning.json"), 200);
        return new SkillsService(repository, clock);
    }
}
