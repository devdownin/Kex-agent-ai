// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.automation;

import java.time.Instant;

public record AutomationAudit(String id, String automationId, Instant at, String actor,
                              String action, String result) {
}
