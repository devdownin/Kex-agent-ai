// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** La clé primaire arbitre aussi deux créations concurrentes depuis deux répliques distinctes. */
class JdbcProcessDefinitionRepository implements ProcessDefinitionRepository {

    private final JdbcTemplate jdbc;

    JdbcProcessDefinitionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<MonitoredProcess> all() {
        return jdbc.query("SELECT id, name, description, hint FROM kex_supervision_process ORDER BY id",
                (rs, index) -> new MonitoredProcess(rs.getString("id"), rs.getString("name"),
                        rs.getString("description"), rs.getString("hint"), null));
    }

    @Override
    public void create(MonitoredProcess process) {
        try {
            jdbc.update("INSERT INTO kex_supervision_process (id, name, description, hint) VALUES (?, ?, ?, ?)",
                    process.id(), process.name(), process.description(), process.hint());
        }
        catch (DuplicateKeyException ex) {
            throw new ProcessDefinitionConflict(process.id());
        }
    }
}
