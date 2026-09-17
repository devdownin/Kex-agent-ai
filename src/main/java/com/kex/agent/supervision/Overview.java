// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.util.List;

/**
 * Charge utile de la vue d'ensemble : de quoi répondre en quelques secondes à « tout fonctionne-t-il,
 * qu'est-ce qui demande mon attention, l'agent a-t-il décidé quelque chose ». Une seule requête,
 * pour que les compteurs et les listes qu'ils annoncent ne puissent pas se contredire à l'écran.
 *
 * @param alerts anomalies dédupliquées et priorisées, pas les relevés bruts : deux cycles qui
 *               voient le même symptôme signalent un incident, pas deux
 * @param maintenance fenêtres de maintenance actives : les processus qu'elles couvrent n'ont
 *               produit ni alerte ni décision au dernier cycle, même en anomalie réelle
 * @param incidents concomitance d'anomalies sur plusieurs processus au même cycle — un signal
 *               grossier de cause commune, pas une causalité établie, vide la plupart du temps
 */
public record Overview(AgentStatus agent, int processesMonitored, int processesOk, int processesWarning,
                       int processesError, int processesUnknown, int anomaliesDetected,
                       int pendingApprovals, List<ProcessSnapshot> processes, List<Alert> alerts,
                       List<Decision> pending, CycleReport lastCycle, List<MaintenanceWindow> maintenance,
                       List<CorrelatedIncident> incidents) {
}
