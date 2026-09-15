// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Instant;

/**
 * @param analysing  un cycle est en cours : l'interface l'affiche plutôt que de laisser croire à
 *                   une inactivité
 * @param staleSince  depuis quand les données affichées sont jugées obsolètes, {@code null} si
 *                    elles sont fraîches
 * @param stateReason pourquoi l'état n'est pas {@code OPERATIONAL}, {@code null} sinon. Un
 *                    « DÉGRADÉ » sans motif envoie chercher la cause dans les journaux ; celle-ci
 *                    est connue au moment où l'état est calculé
 */
public record AgentStatus(AgentState state, ExecutionMode mode, boolean paused, boolean analysing,
                          Instant lastCycleAt, String lastCycleId, Instant staleSince,
                          String policyVersion, double confidenceThreshold, String stateReason) {
}
