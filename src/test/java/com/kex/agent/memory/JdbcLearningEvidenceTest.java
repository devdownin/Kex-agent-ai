// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.kex.agent.testsupport.FlywayTestSchema;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcLearningEvidenceTest {
    @Test void persists_evidence_and_atomic_retirement_across_replicas() {
        var data = new SimpleDriverDataSource(); data.setDriverClass(org.h2.Driver.class);
        data.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        FlywayTestSchema.migrate(data);
        var first = new JdbcLearningRepository(new JdbcTemplate(data), 100);
        var next = new JdbcLearningRepository(new JdbcTemplate(data), 100);
        Instant now = Instant.parse("2026-10-04T06:00:00Z");
        var proof = new LearningEvidence("VERIFIED", "task:1", now, now.plusSeconds(3600), List.of(), "v1",
                Map.of("topic", "orders"), List.of("read access"), List.of("/lag == 0"));
        var skill = new LearningEntry("skill", "tenant", "SKILL", "Lag", "Procedure", "sha256:abc", "task:1",
                now, "PENDING", null, null, null, proof);
        first.add(skill);
        assertThat(next.pending("SKILL")).containsExactly(skill);
        assertThat(next.review("tenant", "skill", "APPROVED", "admin", now, "Measured")).isTrue();
        var approved = first.list("tenant", "SKILL", Instant.EPOCH).getFirst();
        assertThat(approved.verification()).isEqualTo(proof);
        assertThat(first.replace(approved.reviewed("RETIRED", "admin", now, "Incorrect"), "APPROVED")).isTrue();
        assertThat(next.replace(approved.reviewed("RETIRED", "other", now, "stale"), "APPROVED")).isFalse();
        assertThat(next.list("tenant", "SKILL", Instant.EPOCH).getFirst().reviewReason()).isEqualTo("Incorrect");
        assertThat(next.list("other", "SKILL", Instant.EPOCH)).isEmpty();
    }
}
