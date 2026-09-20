// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.automation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AutomationServiceTest {

    private final JdbcAutomationRepository repository = mock(JdbcAutomationRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneId.of("UTC"));
    private AutomationProperties properties;
    private AutomationService service;

    @BeforeEach
    void setUp() {
        properties = new AutomationProperties(true, Duration.ofMinutes(2), 5, 1000, Set.of("tool1"));
        service = new AutomationService(repository, properties, clock);
    }

    @Test
    void creates_and_updates_and_deletes_automation() {
        AutomationRequest req = new AutomationRequest("task1", "prompt1", "0 * * * * *", "UTC", true);
        Automation expected = new Automation("id-1", "user1", "task1", "prompt1", "0 * * * * *", "UTC",
                true, clock.instant(), null, "IDLE", "", clock.instant(), clock.instant());

        given(repository.create(eq("user1"), eq(req), any(), any())).willReturn(expected);
        given(repository.update(eq("user1"), eq("id-1"), eq(req), any(), any())).willReturn(expected);
        given(repository.list("user1")).willReturn(List.of(expected));
        given(repository.audit("user1")).willReturn(List.of());

        Automation created = service.create("user1", req);
        assertThat(created).isEqualTo(expected);

        Automation updated = service.update("user1", "id-1", req);
        assertThat(updated).isEqualTo(expected);

        assertThat(service.list("user1")).containsExactly(expected);
        assertThat(service.audit("user1")).isEmpty();

        service.delete("user1", "id-1");
        verify(repository).delete("user1", "id-1", clock.instant());
    }

    @Test
    void tick_executes_due_tasks_and_handles_success_and_failure() {
        Automation task = new Automation("id-1", "user1", "task1", "prompt1", "0 * * * * *", "UTC",
                true, clock.instant(), null, "IDLE", "", clock.instant(), clock.instant());
        JdbcAutomationRepository.Candidate candidate = new JdbcAutomationRepository.Candidate(task, 1L);
        JdbcAutomationRepository.Claim claim = new JdbcAutomationRepository.Claim(task, "token-1");

        given(repository.due(any(), eq(5))).willReturn(List.of(candidate));
        given(repository.claim(eq(candidate), any(), any())).willReturn(Optional.of(claim));

        ScheduledTaskExecutor executor = (owner, id, prompt) -> "Task executed successfully";

        service.tick(executor);
        verify(repository).finish(claim, "SUCCEEDED", "Task executed successfully", clock.instant());

        // Failing executor
        ScheduledTaskExecutor failingExecutor = (owner, id, prompt) -> {
            throw new RuntimeException("Execution failed");
        };

        service.tick(failingExecutor);
        verify(repository).finish(claim, "FAILED", "Tâche échouée ; consulter l’audit.", clock.instant());
    }
}
