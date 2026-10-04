// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.execution.DurableTask;
import com.kex.agent.execution.TaskPlan;
import com.kex.agent.skills.CharterService;
import com.kex.agent.skills.SkillsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class VerifiedLearningTest {
    private static final Instant NOW = Instant.parse("2026-10-04T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, java.time.ZoneOffset.UTC);
    private static final MemoryProperties PROPERTIES = new MemoryProperties(true, 100, 1000, Duration.ofDays(7),
            new MemoryProperties.Skills(5, 3000, Duration.ofDays(90)));
    private static DurableTask task(DurableTask.Status status, DurableTask.StepStatus stepStatus) {
        TaskPlan plan = new TaskPlan("Check lag", List.of("Read permission"), List.of(new TaskPlan.Step(
                "check", "Measure lag", "lag", Map.of("topic", "orders"), List.of(), new TaskPlan.Expectation("/lag", 0))));
        return new DurableTask("task-1", "tenant", "operator", 1, plan, "contract-v1", status,
                List.of(new DurableTask.StepResult("check", stepStatus, "{\"lag\":0}", "abc", NOW, "lag == 0")),
                "admin", "policy-v1", null, null, NOW, NOW, "done");
    }
    @Test void only_verified_results_become_pending_skills_and_survive_restart(@TempDir Path directory) {
        Path file = directory.resolve("learning.json");
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var repository = new FileLearningRepository(json, file, 100);
        var skills = new SkillsService(repository, CLOCK);
        var memory = new LongTermMemoryService(repository, skills, new CharterService(repository, CLOCK), PROPERTIES, CLOCK);
        memory.recordSuccessfulTask("tenant", "chat", "check", "Success!", List.of());
        memory.recordVerifiedTask(task(DurableTask.Status.COMPLETED, DurableTask.StepStatus.COMPLETED));
        assertThat(skills.list("tenant")).isEmpty();
        assertThat(memory.context("tenant")).isEmpty();
        memory.recordVerifiedTask(task(DurableTask.Status.VERIFIED, DurableTask.StepStatus.VERIFIED));
        var skill = skills.list("tenant").getFirst();
        assertThat(skill.status()).isEqualTo("PENDING");
        assertThat(skill.verification().parameters()).containsKey("check");
        assertThat(skill.verification().version()).isEqualTo("contract-v1");
        assertThat(skills.approved("tenant")).isEmpty();
        skills.review(skill.id(), true, "admin", "Measured");
        var restarted = new FileLearningRepository(json, file, 100);
        assertThat(new SkillsService(restarted, CLOCK).approved("tenant")).hasSize(1);
        assertThat(new SkillsService(restarted, Clock.offset(CLOCK, Duration.ofDays(8))).approved("tenant")).isEmpty();
        String summary = memory.summaries("tenant").stream().filter(e -> "VERIFIED".equals(e.status())).findFirst().orElseThrow().id();
        memory.contradict("tenant", summary, "admin", "incident:123", "Lag result was incorrect");
        assertThat(memory.context("tenant")).isEmpty();
        assertThat(skills.list("tenant")).extracting(LearningEntry::status).containsExactly("RETIRED");
        assertThat(skills.list("other")).isEmpty();
    }
    @Test void partially_verified_plans_cannot_teach_a_procedure(@TempDir Path directory) {
        var repository = new FileLearningRepository(new ObjectMapper().findAndRegisterModules(), directory.resolve("data.json"), 100);
        var skills = new SkillsService(repository, CLOCK);
        var memory = new LongTermMemoryService(repository, skills, new CharterService(repository, CLOCK), PROPERTIES, CLOCK);
        memory.recordVerifiedTask(task(DurableTask.Status.VERIFIED, DurableTask.StepStatus.COMPLETED));
        assertThat(skills.list("tenant")).isEmpty();
    }
}
