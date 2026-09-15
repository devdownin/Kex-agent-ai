// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.util.List;

/**
 * Charge utile de la vue d'ensemble : de quoi répondre en quelques secondes à « tout fonctionne-t-il,
 * qu'est-ce qui demande mon attention, l'agent a-t-il décidé quelque chose ». Une seule requête,
 * pour que les compteurs et les listes qu'ils annoncent ne puissent pas se contredire à l'écran.
 */
public record Overview(AgentStatus agent, int processesMonitored, int processesOk, int processesWarning,
                       int processesError, int processesUnknown, int anomaliesDetected,
                       int pendingApprovals, List<ProcessSnapshot> processes, List<Anomaly> anomalies,
                       List<Decision> pending, CycleReport lastCycle) {
}
