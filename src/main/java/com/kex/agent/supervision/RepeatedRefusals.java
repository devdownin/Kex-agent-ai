// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.util.List;

/**
 * Plusieurs refus humains concordants sur une même capacité. Publié plutôt qu'appelé directement :
 * la supervision sait compter les verdicts, elle n'a pas à savoir qu'une bibliothèque de compétences
 * existe — et l'inverse vaut aussi, ce qui évite que les deux paquets se tiennent l'un l'autre.
 *
 * <p>Jusqu'ici un refus ne nourrissait que le durcissement du plancher de confiance : l'agent
 * proposait moins souvent, sans jamais apprendre *ce que* l'humain lui reprochait. Le motif de refus
 * est la seule trace écrite de ce reproche.
 *
 * @param actor     l'humain dont les verdicts ont déclenché le constat ; la compétence proposée lui
 *                  appartient, comme les siennes, et c'est à lui qu'elle demande un arbitrage
 * @param decisions les refus retenus, du plus récent au plus ancien
 */
public record RepeatedRefusals(String actor, Capability capability, List<Decision> decisions) {
    public RepeatedRefusals {
        decisions = List.copyOf(decisions);
    }
}
