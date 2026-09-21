// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.automation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.kex.agent.testsupport.FlywayTestSchema;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcAutomationRepositoryTest {

    private JdbcAutomationRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:test-automation-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        FlywayTestSchema.migrate(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcAutomationRepository(jdbc);
    }

    @Test
    void performs_crud_due_claim_finish_and_audit() {
        Instant now = Instant.parse("2026-09-20T12:00:00Z");
        Instant next = now.plusSeconds(3600);

        AutomationRequest req = new AutomationRequest("Task 1", "prompt 1", "0 * * * * *", "UTC", true);

        Automation created = repository.create("user1", req, now, next);
        assertThat(created.name()).isEqualTo("Task 1");

        Automation found = repository.get("user1", created.id());
        assertThat(found.id()).isEqualTo(created.id());

        List<Automation> list = repository.list("user1");
        assertThat(list).hasSize(1);

        AutomationRequest updateReq = new AutomationRequest("Task 1 Updated", "prompt 1", "0 * * * * *", "UTC", true);
        Automation updated = repository.update("user1", created.id(), updateReq, now, next);
        assertThat(updated.name()).isEqualTo("Task 1 Updated");

        // Due and claim
        List<JdbcAutomationRepository.Candidate> due = repository.due(now.plusSeconds(4000), 10);
        assertThat(due).hasSize(1);

        Instant claimNow = now.plusSeconds(4000);
        Optional<JdbcAutomationRepository.Claim> claimOpt = repository.claim(due.get(0), claimNow, Duration.ofMinutes(2));
        assertThat(claimOpt).isPresent();

        JdbcAutomationRepository.Claim claim = claimOpt.get();
        repository.finish(claim, "SUCCEEDED", "Done result", now.plusSeconds(10));

        List<AutomationAudit> audit = repository.audit("user1");
        assertThat(audit).isNotEmpty();

        repository.delete("user1", created.id(), now.plusSeconds(20));
        assertThat(repository.list("user1")).isEmpty();
    }
}
