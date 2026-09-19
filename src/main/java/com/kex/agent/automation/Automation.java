// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.automation;

import java.time.Instant;

public record Automation(String id, String owner, String name, String prompt, String cron, String zone,
                         boolean enabled, Instant nextRun, Instant lastRun, String status, String result,
                         Instant createdAt, Instant updatedAt) {
}
