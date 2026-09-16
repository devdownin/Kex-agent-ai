// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Défaut : aucune infrastructure de plus que l'agent lui-même, donc mono-instance. */
class InMemoryMemoryRepository implements MemoryRepository {

    private final Deque<MemoryEntry> entries = new ArrayDeque<>();
    private final int capacity;

    InMemoryMemoryRepository(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    @Override
    public synchronized void add(MemoryEntry entry) {
        entries.addFirst(entry);
        if (entries.size() > capacity) {
            entries.removeLast();
        }
    }

    @Override
    public synchronized List<MemoryEntry> active(Instant since) {
        return entries.stream().filter(entry -> !entry.createdAt().isBefore(since)).toList();
    }
}
