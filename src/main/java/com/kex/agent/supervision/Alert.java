// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Instant;
import java.util.List;

/**
 * Une anomalie <em>dédupliquée</em>. Deux cycles qui relèvent le même symptôme sur le même
 * processus signalent un incident, pas deux : l'alerte porte alors un compteur et une date de
 * première apparition plutôt que de s'empiler une seconde fois dans la liste.
 *
 * @param id               identité stable d'un cycle à l'autre — c'est elle qui permet le
 *                         regroupement, là où l'identifiant d'une anomalie change à chaque relevé
 * @param occurrences      nombre de relevés dans la fenêtre d'historique conservée
 * @param pendingDecisionId décision en attente de validation rattachée, {@code null} sinon : une
 *                         alerte actionnable doit porter son action, pas obliger à la chercher
 * @param decisionIds       toutes les décisions rattachées aux occurrences de cette alerte ; cette
 *                         association explicite évite de rapprocher deux symptômes distincts d'un
 *                         même processus
 */
public record Alert(String id, String processId, String processName, String title,
                    ProcessState severity, int occurrences, Instant firstSeenAt, Instant lastSeenAt,
                    List<Observation> observations, String analysis, String probableCause,
                    double confidence, String recommendation, Capability capability,
                    String pendingDecisionId, List<String> decisionIds, String knowledgeReference) {

    /** Sépare deux titres identiques sur des processus différents, sans dépendre d'un compteur. */
    static String identity(String processId, String title) {
        return processId + "::" + title;
    }
}
