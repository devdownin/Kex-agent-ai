// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Instant;

/**
 * Fenêtre pendant laquelle un processus déclaré ne produit ni alerte ni décision, même en anomalie
 * réelle — un déploiement connu ne doit pas se lire comme un incident. En mémoire du processus,
 * comme la pause de l'agent : elle ne prétend décrire qu'une intention passagère, pas un état à
 * répliquer entre instances.
 */
public record MaintenanceWindow(String processId, String processName, Instant until, String reason,
                                String declaredBy) {
}
