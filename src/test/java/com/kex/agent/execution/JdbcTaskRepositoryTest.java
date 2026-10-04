// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.execution;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcTaskRepositoryTest {
    @Test void two_repositories_atomically_claim_same_revision_and_preserve_owner_scope() throws Exception {
        var database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        try {
            try (var connection = database.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V4__durable_tasks.sql"));
            }
            var json = new ObjectMapper().findAndRegisterModules();
            var first = new JdbcTaskRepository(new JdbcTemplate(database), json);
            var second = new JdbcTaskRepository(new JdbcTemplate(database), json);
            var now = Instant.now();
            var task = new DurableTask(UUID.randomUUID().toString(), "team", "actor", 0,
                    new TaskPlan("read", List.of(), List.of()), "fingerprint", DurableTask.Status.APPROVED,
                    List.of(), "actor", "v1", null, null, now, now, "approved");
            first.create(task);
            var claimed = new DurableTask(task.id(), "team", "actor", 1, task.plan(), "fingerprint", DurableTask.Status.RUNNING,
                    List.of(), "actor", "v1", "first-worker", now.plusSeconds(60), now, now, "claimed");
            assertThat(first.replace(claimed, 0)).isTrue();
            assertThat(second.replace(claimed, 0)).isFalse();
            assertThat(second.find("other-team", task.id())).isEmpty();
            assertThat(second.find("team", task.id()).orElseThrow().worker()).isEqualTo("first-worker");
            assertThat(second.list("team")).hasSize(1);
        } finally { database.shutdown(); }
    }
}
