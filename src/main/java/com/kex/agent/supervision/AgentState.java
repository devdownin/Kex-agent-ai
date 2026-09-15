// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

/** État de l'agent affiché en permanence dans l'en-tête, jamais porté par la seule couleur. */
public enum AgentState {

    OPERATIONAL,

    /**
     * Rien de mesuré. Distinct de {@link #OPERATIONAL}, qui affirme que tout va bien : un agent
     * qui n'a jamais rien analysé n'en sait rien, et un vert en tête d'écran le dirait quand même.
     */
    UNKNOWN,

    DEGRADED,
    ERROR,
    PAUSED,
    ANALYSING
}
