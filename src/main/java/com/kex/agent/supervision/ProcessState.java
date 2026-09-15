// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

/**
 * État d'un processus surveillé. {@code UNKNOWN} n'est pas un détail : une donnée manquante et une
 * donnée saine se ressemblent dans un tableau de bord, et les confondre fait rater une panne.
 */
public enum ProcessState {

    OK,
    WARNING,
    ERROR,
    UNKNOWN
}
