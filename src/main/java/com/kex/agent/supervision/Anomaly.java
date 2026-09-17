// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Instant;
import java.util.List;

/**
 * @param confidence  confiance de l'agent, entre 0 et 1. Ce n'est pas une probabilité mesurée :
 *                    l'interface l'affiche toujours avec les observations qui la fondent, pour
 *                    qu'elle se vérifie plutôt qu'elle ne se croie.
 * @param capability  l'action recommandée, {@code null} quand l'agent ne recommande rien d'autre
 *                    que de regarder
 * @param knowledgeReference note de connaissance interne citée par le modèle à l'appui de son
 *                    analyse, {@code null} sinon. Rapportée telle quelle : ni vérifiée ni retrouvée
 *                    mécaniquement, un modèle qui l'invente reste possible — voir ARCHITECTURE.md
 */
public record Anomaly(String id, String cycleId, String processId, String processName, String title,
                      ProcessState severity, List<Observation> observations, String analysis,
                      String probableCause, double confidence, String recommendation,
                      Capability capability, Instant detectedAt, String knowledgeReference) {
}
