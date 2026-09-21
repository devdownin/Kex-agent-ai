// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class FileLearningRepository implements LearningRepository {
    private final DurableFiles file;
    private final int capacity;
    private List<LearningEntry> entries;

    public FileLearningRepository(ObjectMapper mapper, Path path, int capacity) {
        file = new DurableFiles(mapper, path);
        this.capacity = Math.max(1, capacity);
        entries = List.of(file.read(LearningEntry[].class, new LearningEntry[0]));
    }

    @Override
    public synchronized void add(LearningEntry entry) {
        List<LearningEntry> next = new ArrayList<>(entries);
        next.add(0, entry);
        // Summaries and skill proposals have independent bounds; summaries cannot evict skills.
        List<LearningEntry> sameKind = next.stream().filter(e -> e.kind().equals(entry.kind())).toList();
        if (sameKind.size() > capacity) next.remove(sameKind.getLast());
        save(next);
    }

    @Override
    public synchronized List<LearningEntry> list(String owner, String kind, Instant since) {
        return entries.stream().filter(entry -> owner.equals(entry.owner()) && kind.equals(entry.kind()))
                .filter(entry -> !entry.createdAt().isBefore(since)).toList();
    }

    @Override
    public synchronized boolean review(String owner, String id, String status, String actor,
                                       Instant at, String reason) {
        List<LearningEntry> next = new ArrayList<>(entries);
        for (int i = 0; i < next.size(); i++) {
            LearningEntry entry = next.get(i);
            if (entry.id().equals(id) && entry.owner().equals(owner) && entry.kind().equals("SKILL")
                    && entry.status().equals("PENDING")) {
                next.set(i, entry.reviewed(status, actor, at, reason));
                save(next);
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized boolean delete(String owner, String id) {
        List<LearningEntry> next = entries.stream()
                .filter(entry -> !(entry.id().equals(id) && entry.owner().equals(owner))).toList();
        if (next.size() == entries.size()) return false;
        save(next);
        return true;
    }

    @Override
    public synchronized Optional<String> ownerOf(String id) {
        return entries.stream().filter(entry -> entry.id().equals(id))
                .map(LearningEntry::owner).findFirst();
    }

    @Override
    public synchronized List<LearningEntry> pending(String kind) {
        return entries.stream()
                .filter(entry -> kind.equals(entry.kind()) && "PENDING".equals(entry.status()))
                .sorted(Comparator.comparing(LearningEntry::createdAt))
                .toList();
    }

    private void save(List<LearningEntry> next) {
        file.write(next);
        entries = List.copyOf(next);
    }
}
