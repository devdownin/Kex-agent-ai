// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

/** La décision n'attend plus de validation : elle a déjà été exécutée, refusée ou bloquée. */
public class DecisionNotPendingException extends RuntimeException {

    public DecisionNotPendingException(String id, DecisionStatus status) {
        super("La décision %s n'attend pas de validation (%s)".formatted(id, status));
    }
}
