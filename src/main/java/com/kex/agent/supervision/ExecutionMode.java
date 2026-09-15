// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

/**
 * Mode d'exécution global. Il ne peut que <em>restreindre</em> l'autonomie d'une capacité, jamais
 * l'élargir : sans cette règle, passer le mode en automatique ouvrirait d'un coup des actions que
 * quelqu'un avait délibérément mises sous supervision.
 */
public enum ExecutionMode {

    AUTOMATIC,
    SUPERVISED,
    MANUAL
}
