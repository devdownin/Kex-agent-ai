// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

/** L'agent est en pause : aucun cycle ne part tant qu'un humain ne l'a pas repris. */
public class AgentPausedException extends RuntimeException {

    public AgentPausedException() {
        super("L'agent est en pause : aucune analyse n'est exécutée");
    }
}
