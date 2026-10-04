// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.execution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.memory.DurableFiles;

/** Single-instance persistence; shared deployments must use the JDBC profile. */
public final class FileTaskRepository implements TaskRepository {
    private final ObjectMapper mapper;
    private final Path directory;
    public FileTaskRepository(ObjectMapper mapper, Path directory) {
        this.mapper = mapper; this.directory = directory;
    }
    private Path path(String id) {
        if (!id.matches("[a-f0-9-]{36}")) throw new TaskConflictException("Tâche inconnue");
        return directory.resolve(id + ".json");
    }
    @Override public synchronized void create(DurableTask task) {
        if (Files.exists(path(task.id()))) throw new TaskConflictException("Identifiant de tâche déjà utilisé");
        new DurableFiles(mapper, path(task.id())).write(task);
    }
    @Override public synchronized Optional<DurableTask> find(String owner, String id) {
        DurableTask task = new DurableFiles(mapper, path(id)).read(DurableTask.class, null);
        return task != null && task.owner().equals(owner) ? Optional.of(task) : Optional.empty();
    }
    @Override public synchronized List<DurableTask> list(String owner) {
        if (!Files.exists(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().matches("[a-f0-9-]{36}\\.json"))
                    .map(p -> new DurableFiles(mapper, p).read(DurableTask.class, null))
                    .filter(t -> t != null && t.owner().equals(owner))
                    .sorted(Comparator.comparing(DurableTask::createdAt).reversed()).limit(100).toList();
        } catch (IOException ex) { throw new IllegalStateException("Lecture des tâches impossible", ex); }
    }
    @Override public synchronized boolean replace(DurableTask task, long expectedRevision) {
        var current = find(task.owner(), task.id());
        if (current.isEmpty() || current.get().revision() != expectedRevision) return false;
        new DurableFiles(mapper, path(task.id())).write(task);
        return true;
    }
}
