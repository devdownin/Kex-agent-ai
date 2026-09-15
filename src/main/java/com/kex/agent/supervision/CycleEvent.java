// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Instant;

/** Une étape du cycle, consultable pour remonter aux données sources. */
public record CycleEvent(Instant at, String label, String detail) {
}
