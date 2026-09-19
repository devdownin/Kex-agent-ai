// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.automation;

/** Implementations must enforce the administrator's read-only tool allowlist at execution time. */
@FunctionalInterface
public interface ScheduledTaskExecutor {
    String execute(String owner, String conversationId, String prompt);
}
