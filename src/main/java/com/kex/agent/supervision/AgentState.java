// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

/** État de l'agent affiché en permanence dans l'en-tête, jamais porté par la seule couleur. */
public enum AgentState {

    OPERATIONAL,
    DEGRADED,
    ERROR,
    PAUSED,
    ANALYSING
}
