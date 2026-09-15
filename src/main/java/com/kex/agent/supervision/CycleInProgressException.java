// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

/**
 * Un cycle est déjà en cours. Refuser vaut mieux que mettre en file : deux analyses simultanées
 * produiraient deux jeux de décisions sur les mêmes faits, donc des actions en double.
 */
public class CycleInProgressException extends RuntimeException {

    public CycleInProgressException() {
        super("Une analyse est déjà en cours");
    }
}
