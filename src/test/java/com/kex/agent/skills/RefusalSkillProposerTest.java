// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.time.Instant;
import java.util.List;

import com.kex.agent.memory.LearningEntry;
import com.kex.agent.supervision.Capability;
import com.kex.agent.supervision.Decision;
import com.kex.agent.supervision.DecisionStatus;
import com.kex.agent.supervision.RepeatedRefusals;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RefusalSkillProposerTest {

    private final SkillsService skills = mock(SkillsService.class);
    private final RefusalSkillProposer proposer = new RefusalSkillProposer(skills);

    @Test
    void propose_une_competence_qui_reprend_les_motifs_de_refus() {
        given(skills.list("opérateur")).willReturn(List.of());

        proposer.onRepeatedRefusals(refusals("Trop risqué en heures ouvrées", "Le consumer rattrape seul"));

        org.mockito.ArgumentCaptor<String> markdown = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> evidence = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(skills).propose(eq("opérateur"), eq("Refus répétés sur RESTART_CONSUMER"), markdown.capture(),
                evidence.capture(), any());
        assertThat(markdown.getValue())
                .contains("Trop risqué en heures ouvrées")
                .contains("Le consumer rattrape seul")
                .contains("ne modifie ni l'autonomie, ni les permissions");
        assertThat(evidence.getValue()).contains("Refus humains : 2").contains("Capacité : RESTART_CONSUMER");
    }

    /** Sans ce garde, chaque refus suivant empilerait la même règle dans la file de revue. */
    @Test
    void ne_propose_pas_deux_fois_la_meme_regle() {
        RepeatedRefusals refusals = refusals("Trop risqué en heures ouvrées");
        proposer.onRepeatedRefusals(refusals);
        String proposed = capturedMarkdown();
        given(skills.list("opérateur")).willReturn(List.of(pending(proposed)));

        proposer.onRepeatedRefusals(refusals);

        verify(skills, org.mockito.Mockito.times(1))
                .propose(any(), any(), any(), any(), any());
    }

    /** Une règle déjà rejetée décrivait l'état d'alors : de nouveaux refus méritent un nouvel avis. */
    @Test
    void repropose_une_regle_precedemment_rejetee() {
        RepeatedRefusals refusals = refusals("Trop risqué en heures ouvrées");
        proposer.onRepeatedRefusals(refusals);
        String proposed = capturedMarkdown();
        given(skills.list("opérateur")).willReturn(List.of(
                new LearningEntry("id", "opérateur", "SKILL", "Refus répétés sur RESTART_CONSUMER", proposed,
                        "preuve", null, Instant.EPOCH, "REJECTED", "admin", Instant.EPOCH, "non")));

        proposer.onRepeatedRefusals(refusals);

        verify(skills, org.mockito.Mockito.times(2)).propose(any(), any(), any(), any(), any());
    }

    @Test
    void remplace_un_motif_absent_par_une_mention_explicite() {
        given(skills.list("opérateur")).willReturn(List.of());

        proposer.onRepeatedRefusals(new RepeatedRefusals("opérateur", Capability.RESTART_CONSUMER,
                List.of(decision("d1", null))));

        assertThat(capturedMarkdown()).contains("non précisé");
        verify(skills, never()).retire(any(), any(), any(), any());
    }

    private String capturedMarkdown() {
        org.mockito.ArgumentCaptor<String> markdown = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(skills, org.mockito.Mockito.atLeastOnce())
                .propose(any(), any(), markdown.capture(), any(), any());
        return markdown.getValue();
    }

    private static LearningEntry pending(String markdown) {
        return new LearningEntry("id", "opérateur", "SKILL", "Refus répétés sur RESTART_CONSUMER", markdown,
                "preuve", null, Instant.EPOCH, "PENDING", null, null, null);
    }

    private static RepeatedRefusals refusals(String... reasons) {
        List<Decision> decisions = new java.util.ArrayList<>();
        for (int i = 0; i < reasons.length; i++) {
            decisions.add(decision("d" + i, reasons[i]));
        }
        return new RepeatedRefusals("opérateur", Capability.RESTART_CONSUMER, decisions);
    }

    private static Decision decision(String id, String reason) {
        return new Decision(id, "cycle", "anomalie", "p1", "Processus", Capability.RESTART_CONSUMER, "objectif",
                "contexte", "Redémarrer le consumer", List.of(), "impact", 0.9,
                DecisionStatus.REJECTED, reason, "policy-v1", "corr", "opérateur",
                Instant.EPOCH, Instant.EPOCH, null);
    }
}
