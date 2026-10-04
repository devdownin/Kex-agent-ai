// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.memory.DurableFiles;
import org.springframework.jdbc.core.JdbcTemplate;

/** Fichiers privés en mono-instance ; CAS SQL dans le profil shared-memory. */
public final class WorkspaceStore {
    private final ObjectMapper mapper;
    private final Path directory;
    private final JdbcTemplate jdbc;
    public WorkspaceStore(ObjectMapper mapper, Path directory) { this.mapper = mapper; this.directory = directory; this.jdbc = null; }
    public WorkspaceStore(ObjectMapper mapper, JdbcTemplate jdbc) { this.mapper = mapper; this.jdbc = jdbc; this.directory = null; }
    private Path path(String owner, String id) { return directory.resolve(owner).resolve(id + ".json"); }
    public synchronized Optional<WorkspaceRequest> find(String owner, String id) {
        if (!id.matches("[a-f0-9-]{36}")) return Optional.empty();
        if (jdbc != null) return jdbc.query("SELECT payload FROM kex_workspace_request WHERE owner = ? AND id = ?",
                (rs, row) -> read(rs.getString(1)), owner, id).stream().findFirst();
        return Optional.ofNullable(new DurableFiles(mapper, path(owner, id)).read(WorkspaceRequest.class, null));
    }
    public synchronized List<WorkspaceRequest> list(String owner) {
        if (jdbc != null) return jdbc.query("SELECT payload FROM kex_workspace_request WHERE owner = ? ORDER BY updated_at DESC, id DESC LIMIT 100",
                (rs, row) -> read(rs.getString(1)), owner);
        Path bucket = directory.resolve(owner);
        if (!Files.exists(bucket)) return List.of();
        try (var files = Files.list(bucket)) {
            return files.filter(p -> p.getFileName().toString().matches("[a-f0-9-]{36}\\.json"))
                    .map(p -> new DurableFiles(mapper, p).read(WorkspaceRequest.class, null))
                    .sorted(Comparator.comparing(WorkspaceRequest::updatedAt).reversed()).limit(100).toList();
        } catch (IOException ex) { throw new IllegalStateException("Historique utilisateur indisponible", ex); }
    }
    public synchronized void create(String owner, WorkspaceRequest request) {
        if (jdbc != null) jdbc.update("INSERT INTO kex_workspace_request (id, owner, revision, updated_at, payload) VALUES (?, ?, ?, ?, ?)",
                request.id(), owner, request.revision(), Timestamp.from(request.updatedAt()), json(request));
        else new DurableFiles(mapper, path(owner, request.id())).write(request);
    }
    public synchronized boolean replace(String owner, WorkspaceRequest request, long revision) {
        if (jdbc != null) return jdbc.update("UPDATE kex_workspace_request SET revision = ?, updated_at = ?, payload = ? WHERE owner = ? AND id = ? AND revision = ?",
                request.revision(), Timestamp.from(request.updatedAt()), json(request), owner, request.id(), revision) == 1;
        var previous = find(owner, request.id());
        if (previous.isEmpty() || previous.get().revision() != revision) return false;
        new DurableFiles(mapper, path(owner, request.id())).write(request); return true;
    }
    private String json(WorkspaceRequest request) {
        try { return mapper.writeValueAsString(request); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Historique non sérialisable", ex); }
    }
    private WorkspaceRequest read(String value) {
        try { return mapper.readValue(value, WorkspaceRequest.class); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Historique persisté invalide", ex); }
    }
}
