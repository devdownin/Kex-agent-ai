// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Instant;
import java.util.List;

/** Instantané lisible d'un cycle en cours, sans inventer d'étape côté navigateur. */
public record CycleProgress(String id, Instant startedAt, List<CycleEvent> events) {

    public CycleProgress {
        events = List.copyOf(events);
    }
}
