// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.util.List;

/**
 * L'audit, seul, mérite de survivre à une instance : c'est la pièce de conformité, là où cycles,
 * anomalies et décisions restent un tableau de bord opérationnel que chaque réplique peut se
 * permettre de tenir à sa façon. {@link InMemoryAuditRepository} est le défaut ; le profil
 * {@code shared-memory} bascule sur {@link JdbcAuditRepository}.
 */
interface AuditRepository {

    void add(AuditEntry entry);

    /** Du plus récent au plus ancien, borné à {@code limit} entrées. */
    List<AuditEntry> recent(int limit);
}
