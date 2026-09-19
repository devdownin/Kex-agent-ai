// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.automation;

final class UnknownAutomationException extends RuntimeException {
    UnknownAutomationException() {
        super("Automatisation introuvable");
    }
}
