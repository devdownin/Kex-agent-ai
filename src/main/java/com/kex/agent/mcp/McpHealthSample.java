// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.time.Instant;

public record McpHealthSample(Instant checkedAt, boolean healthy, Long latencyMillis, String message) {
}
