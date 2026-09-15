// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Instant;
import java.util.List;

/**
 * @param failure message d'échec du cycle, {@code null} s'il est allé au bout. Un cycle raté est
 *                conservé comme les autres : c'est lui qui explique pourquoi les données affichées
 *                datent.
 */
public record CycleReport(String id, Instant startedAt, Instant finishedAt, int processesAnalysed,
                          int anomaliesDetected, int decisionsTaken, int pendingApprovals,
                          List<CycleEvent> events, String failure) {
}
