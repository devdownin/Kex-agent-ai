// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.util.List;

import com.kex.agent.supervision.Decision;
import com.kex.agent.supervision.RepeatedRefusals;
import org.springframework.context.event.EventListener;

/**
 * Transforme des refus humains répétés en compétence candidate. Une compétence naissait jusqu'ici
 * d'une réussite — sa preuve est « l'échange s'est bien terminé » — si bien que les seuls verdicts
 * écrits par un humain, les motifs de refus, ne laissaient aucune trace réutilisable.
 *
 * <p>Le texte proposé est volontairement déterministe : les mêmes refus produisent exactement le
 * même Markdown, ce qui permet de reconnaître une proposition déjà en attente ou déjà approuvée
 * plutôt que d'en empiler une par refus — le même garde-fou que pour les procédures issues d'une
 * réussite. Aucun appel au modèle ici : une proposition reformulée à chaque passage échapperait à
 * cette comparaison, et la revue humaine verrait dix fois la même règle.
 */
public final class RefusalSkillProposer {
    private final SkillsService skills;

    public RefusalSkillProposer(SkillsService skills) {
        this.skills = skills;
    }

    @EventListener
    public void onRepeatedRefusals(RepeatedRefusals refusals) {
        String title = "Refus répétés sur " + refusals.capability();
        String markdown = procedure(refusals);
        // Déjà en revue ou déjà approuvée : la règle est connue, seul un nouveau texte mérite un
        // nouvel arbitrage. Une proposition rejetée peut revenir : l'humain a tranché sur l'état
        // d'alors, et de nouveaux refus décrivent une autre situation.
        boolean known = skills.list(refusals.actor()).stream()
                .anyMatch(entry -> entry.markdown().equals(markdown) && !entry.status().equals("REJECTED"));
        if (known) {
            return;
        }
        skills.propose(refusals.actor(), title, markdown, evidence(refusals), null);
    }

    private static String procedure(RepeatedRefusals refusals) {
        StringBuilder markdown = new StringBuilder("# Règle tirée de refus humains\n\n## Quand l'utiliser\n")
                .append("Avant de proposer une action de capacité `").append(refusals.capability())
                .append("`.\n\n## Ce que les refus disent\n");
        refusals.decisions().stream().limit(10).forEach(decision -> markdown
                .append("- ").append(bounded(decision.action(), 200))
                .append(" — motif : ").append(bounded(reason(decision), 400)).append('\n'));
        markdown.append("\n## Conduite à tenir\nReprendre ces motifs avant de proposer une action de cette "
                + "capacité, et renoncer plutôt que de reproposer ce qui a déjà été refusé pour la même "
                + "raison. Cette règle ne modifie ni l'autonomie, ni les permissions, ni les approbations : "
                + "elle ne fait que restreindre ce que l'agent propose.\n");
        return markdown.toString();
    }

    private static String evidence(RepeatedRefusals refusals) {
        List<String> ids = refusals.decisions().stream().limit(10).map(Decision::id).toList();
        return "Refus humains : " + refusals.decisions().size()
                + "\nCapacité : " + refusals.capability()
                + "\nDécisions : " + bounded(String.join(", ", ids), 3000);
    }

    /** Le motif du refus vit dans {@code result} : c'est là que {@code reject} l'écrit. */
    private static String reason(Decision decision) {
        return decision.result() == null || decision.result().isBlank() ? "non précisé" : decision.result();
    }

    private static String bounded(String value, int limit) {
        if (value == null || value.isBlank()) return "non précisé";
        String stripped = value.strip();
        return stripped.length() <= limit ? stripped : stripped.substring(0, limit);
    }
}
