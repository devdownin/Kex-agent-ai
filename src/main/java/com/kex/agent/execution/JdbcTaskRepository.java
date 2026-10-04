// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.execution;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcTaskRepository implements TaskRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public JdbcTaskRepository(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    @Override public void create(DurableTask task) {
        jdbc.update("INSERT INTO kex_task (id, owner, revision, created_at, payload) VALUES (?, ?, ?, ?, ?)",
                task.id(), task.owner(), task.revision(), Timestamp.from(task.createdAt()), json(task));
    }
    @Override public Optional<DurableTask> find(String owner, String id) {
        return jdbc.query("SELECT payload FROM kex_task WHERE owner = ? AND id = ?",
                (rs, row) -> read(rs.getString(1)), owner, id).stream().findFirst();
    }
    @Override public List<DurableTask> list(String owner) {
        return jdbc.query("SELECT payload FROM kex_task WHERE owner = ? ORDER BY created_at DESC, id DESC LIMIT 100",
                (rs, row) -> read(rs.getString(1)), owner);
    }
    @Override public boolean replace(DurableTask task, long expectedRevision) {
        return jdbc.update("UPDATE kex_task SET revision = ?, payload = ? WHERE id = ? AND owner = ? AND revision = ?",
                task.revision(), json(task), task.id(), task.owner(), expectedRevision) == 1;
    }
    private String json(DurableTask task) {
        try { return mapper.writeValueAsString(task); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Sérialisation de tâche impossible", ex); }
    }
    private DurableTask read(String value) {
        try { return mapper.readValue(value, DurableTask.class); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Tâche persistée invalide", ex); }
    }
}
