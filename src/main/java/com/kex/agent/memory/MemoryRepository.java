// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Instant;
import java.util.List;

/**
 * Persistance des souvenirs écrits par le modèle. {@link InMemoryMemoryRepository} est le défaut,
 * mono-instance ; le profil {@code shared-memory} bascule sur {@link JdbcMemoryRepository}, comme
 * l'audit de supervision.
 */
interface MemoryRepository {

    /** Ajoute l'entrée, puis évince la plus ancienne au-delà de la capacité configurée. */
    void add(MemoryEntry entry);

    /** Du plus récent au plus ancien, entrées écrites depuis {@code since} seulement. */
    List<MemoryEntry> active(Instant since);
}
