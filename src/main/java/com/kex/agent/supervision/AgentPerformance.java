// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

/**
 * Mesure de l'agent lui-même, pas du système surveillé : un tableau de bord qui ne dit pas si
 * l'agent se trompe souvent laisse décider de son autonomie à l'aveugle.
 *
 * <p>Tout est calculé sur la fenêtre d'historique conservée en mémoire, pas depuis le premier
 * jour : ce sont des tendances récentes, et l'interface le dit.
 *
 * @param relevanceRate       part des recommandations qu'un humain a approuvées parmi celles qu'il
 *                            a tranchées. {@code null} tant que personne n'a tranché — un taux
 *                            calculé sur zéro verdict serait un chiffre inventé. Les exécutions
 *                            autonomes n'y entrent pas : l'agent ne se confirme pas lui-même.
 * @param averageCycleMillis  durée moyenne d'un cycle. Ce n'est <em>pas</em> un délai de détection :
 *                            celui-ci se mesurerait depuis le début de l'incident, que rien ici ne
 *                            connaît.
 * @param averageResolutionMillis délai moyen entre la décision et son dénouement, quel qu'il soit
 */
public record AgentPerformance(int cycles, int cyclesFailed, Long averageCycleMillis,
                               int anomaliesDetected, int activeAlerts,
                               int decisionsTaken, int autonomousDecisions, int humanApprovals,
                               int humanRejections, Double relevanceRate,
                               int actionsExecuted, int actionsFailed, int actionsBlocked,
                               int approvalsExpired, Long averageResolutionMillis) {
}
