// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Instant;
import java.util.List;

import com.kex.agent.testsupport.FlywayTestSchema;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcLearningRepositoryTest {

    private JdbcLearningRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:test-learning-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        FlywayTestSchema.migrate(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcLearningRepository(jdbc, 10);
    }

    @Test
    void performs_learning_crud_review_and_delete() {
        LearningEntry entry = new LearningEntry("l-1", "user1", "SKILL", "Check Lag", "Markdown steps",
                "Evidence", "conv-1", Instant.parse("2026-09-20T10:00:00Z"), "PENDING", null, null, null);

        repository.add(entry);

        List<LearningEntry> list = repository.list("user1", "SKILL", Instant.EPOCH);
        assertThat(list).containsExactly(entry);

        boolean reviewed = repository.review("user1", "l-1", "APPROVED", "admin", Instant.parse("2026-09-20T11:00:00Z"), "Good skill");
        assertThat(reviewed).isTrue();

        List<LearningEntry> updatedList = repository.list("user1", "SKILL", Instant.EPOCH);
        assertThat(updatedList.get(0).status()).isEqualTo("APPROVED");

        boolean deleted = repository.delete("user1", "l-1");
        assertThat(deleted).isTrue();
        assertThat(repository.list("user1", "SKILL", Instant.EPOCH)).isEmpty();
    }
}
