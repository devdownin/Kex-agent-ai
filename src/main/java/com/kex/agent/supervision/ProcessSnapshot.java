// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Instant;

/**
 * @param delayMillis retard observé, {@code null} quand la donnée manque — l'absence de retard et
 *                    l'absence d'information ne se confondent pas
 * @param coverage    ce que le relevé a réellement couvert. Un état lu sur une passe incomplète ne
 *                    vaut pas un état lu sur une passe complète, et l'écran doit pouvoir le dire
 */
public record ProcessSnapshot(String processId, String name, ProcessState state, Instant lastRun,
                              Long durationMillis, Long delayMillis, String note, Coverage coverage) {
}
