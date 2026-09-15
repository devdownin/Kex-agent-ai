// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

public class UnknownDecisionException extends RuntimeException {

    public UnknownDecisionException(String id) {
        super("Décision inconnue : " + id);
    }
}
