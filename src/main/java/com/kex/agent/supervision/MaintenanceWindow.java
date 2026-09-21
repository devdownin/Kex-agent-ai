// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Instant;

/**
 * Fenêtre pendant laquelle un processus déclaré ne produit ni alerte ni décision, même en anomalie
 * réelle — un déploiement connu ne doit pas se lire comme un incident.
 *
 * <p>Tenue par {@link SupervisionStateRepository}, donc partagée entre répliques sous
 * {@code shared-memory} : déclarée sur une seule, elle ne taisait les alertes que là, et le
 * déploiement redevenait un incident — puis une décision — vu depuis les autres.
 */
public record MaintenanceWindow(String processId, String processName, Instant until, String reason,
                                String declaredBy) {
}
