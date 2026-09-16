// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

public class UnknownMemoryException extends RuntimeException {

    UnknownMemoryException(String id) {
        super("Aucun souvenir actif portant l'identifiant '%s'".formatted(id));
    }
}
