// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

/**
 * Ce que l'agent a le droit de faire, capacité par capacité. La spec impose que l'autonomie se
 * règle par action et non globalement : « redémarrer un consumer » et « modifier une configuration
 * Kafka » n'ont pas le même coût d'erreur.
 */
public enum Autonomy {

    /** L'agent exécute seul, sous réserve du seuil de confiance. */
    AUTOMATIC,

    /** L'agent prépare l'action et attend une validation humaine. */
    SUPERVISED,

    /** L'agent peut détecter et recommander, jamais exécuter. */
    FORBIDDEN
}
