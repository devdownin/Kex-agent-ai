// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

final class FileMemoryRepository implements MemoryRepository {
    private final DurableFiles file;
    private final int capacity;
    private List<MemoryEntry> entries;

    FileMemoryRepository(ObjectMapper mapper, Path path, int capacity) {
        this.file = new DurableFiles(mapper, path);
        this.capacity = Math.max(1, capacity);
        this.entries = List.of(file.read(MemoryEntry[].class, new MemoryEntry[0]));
    }

    @Override
    public synchronized void add(MemoryEntry entry) {
        List<MemoryEntry> next = new ArrayList<>(entries);
        next.add(0, entry);
        if (next.size() > capacity) next.removeLast();
        save(next);
    }

    @Override
    public synchronized boolean supersede(String id, String replacement) {
        List<MemoryEntry> next = new ArrayList<>(entries);
        for (int i = 0; i < next.size(); i++) {
            MemoryEntry entry = next.get(i);
            if (entry.id().equals(id) && entry.supersededBy() == null) {
                next.set(i, new MemoryEntry(id, entry.content(), entry.conversationId(), entry.createdAt(),
                        replacement, entry.owner()));
                save(next);
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized List<MemoryEntry> active(Instant since) {
        return entries.stream().filter(entry -> entry.supersededBy() == null)
                .filter(entry -> !entry.createdAt().isBefore(since)).toList();
    }

    @Override
    public synchronized Optional<MemoryEntry> forget(String id) {
        Optional<MemoryEntry> found = entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
        if (found.isPresent()) save(entries.stream().filter(entry -> !entry.id().equals(id)).toList());
        return found;
    }

    private void save(List<MemoryEntry> next) {
        file.write(next);
        entries = List.copyOf(next);
    }
}
