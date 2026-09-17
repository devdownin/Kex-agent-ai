// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

public class UnknownProcessException extends RuntimeException {

    public UnknownProcessException(String id) {
        super("Processus inconnu : " + id);
    }
}
