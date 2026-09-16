// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.util.List;

/** Défaut : aucune infrastructure de plus que l'agent lui-même, donc mono-instance. */
class InMemoryAuditRepository implements AuditRepository {

    private final History<AuditEntry> entries;

    InMemoryAuditRepository(int historySize) {
        this.entries = new History<>(historySize);
    }

    @Override
    public void add(AuditEntry entry) {
        entries.add(entry);
    }

    @Override
    public List<AuditEntry> recent(int limit) {
        return entries.list();
    }
}
