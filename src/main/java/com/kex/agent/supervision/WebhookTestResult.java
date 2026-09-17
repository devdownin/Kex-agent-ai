// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

/** @param detail motif d'échec ({@link WebhookNotifier#send}), {@code null} en cas de succès */
public record WebhookTestResult(boolean success, String detail) {
}
