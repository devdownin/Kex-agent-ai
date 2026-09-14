// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.time.Duration;

public class AgentTimeoutException extends RuntimeException {

    public AgentTimeoutException(Duration timeout) {
        super("Pas de réponse complète en %ds".formatted(timeout.toSeconds()));
    }
}
