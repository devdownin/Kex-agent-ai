// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Instant;

/**
 * Un point de la tendance d'un processus précis, un par cycle qui l'a relevé. {@code delayMillis}
 * reste {@code null} quand la donnée manque — une absence de retard et une absence de mesure ne se
 * confondent pas, ici comme dans {@link ProcessSnapshot}.
 */
public record ProcessHistoryPoint(String cycleId, Instant at, ProcessState state, Long delayMillis) {
}
