// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

class ProcessDefinitionConflict extends RuntimeException {
    ProcessDefinitionConflict(String id) {
        super("Le processus « " + id + " » existe déjà");
    }
}
