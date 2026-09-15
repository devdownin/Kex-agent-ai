// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

/**
 * Cycle de vie d'une décision. {@code BLOCKED} sépare « l'agent a recommandé mais n'a pas le droit »
 * de « l'agent attend un humain » : la première demande un changement de politique, la seconde un
 * clic.
 */
public enum DecisionStatus {

    PENDING_APPROVAL,
    EXECUTED,
    REJECTED,
    FAILED,
    BLOCKED
}
