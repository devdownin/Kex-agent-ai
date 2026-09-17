// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;

public record MaintenanceRequest(@NotNull Duration duration, String reason) {
}
