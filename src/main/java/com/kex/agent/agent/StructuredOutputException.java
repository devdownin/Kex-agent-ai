// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

public class StructuredOutputException extends RuntimeException {

    public StructuredOutputException(Throwable cause) {
        super("Le modèle n'a pas rendu un JSON conforme au schéma demandé", cause);
    }

    public StructuredOutputException(String detail) {
        super("Le modèle n'a pas rendu un JSON conforme au schéma demandé : " + detail);
    }
}
