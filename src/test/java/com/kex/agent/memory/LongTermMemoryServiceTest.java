// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import com.kex.agent.agent.AgentEvent;
import com.kex.agent.skills.Charter;
import com.kex.agent.skills.CharterService;
import com.kex.agent.skills.SkillsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LongTermMemoryServiceTest {

    private static final MemoryProperties.Skills SKILLS =
            new MemoryProperties.Skills(5, 3000, Duration.ofDays(90));

    private final LearningRepository repository = mock(LearningRepository.class);
    private final SkillsService skills = mock(SkillsService.class);
    private final CharterService charter = mock(CharterService.class);
    private final MemoryProperties properties = new MemoryProperties(true, 100, 1000, Duration.ofDays(7), SKILLS);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneId.of("UTC"));
    private LongTermMemoryService service;

    @BeforeEach
    void setUp() {
        given(charter.current(any())).willReturn(Charter.NONE);
        service = new LongTermMemoryService(repository, skills, charter, properties, clock);
    }

    @Test
    void records_successful_task_summary_and_proposes_procedure() {
        AgentEvent.ToolCall call = new AgentEvent.ToolCall("kex_list_topics", 10L, false);

        service.recordSuccessfulTask("user1", "conv-1", "list topics", "found 5 topics", List.of(call));

        verify(repository).add(any(LearningEntry.class));
        verify(skills).propose(eq("user1"), eq("list topics"), any(), any(), eq("conv-1"));
    }

    @Test
    void fetches_summaries_and_context_and_forgets() {
        LearningEntry summary = new LearningEntry("s-1", "user1", "SUMMARY", "title", "Markdown summary",
                "evidence", "conv-1", clock.instant(), "READY", null, null, null);

        given(repository.list("user1", "SUMMARY", clock.instant().minus(properties.retention())))
                .willReturn(List.of(summary));
        given(skills.approved("user1")).willReturn(List.of());
        given(repository.delete("user1", "s-1")).willReturn(true);

        assertThat(service.summaries("user1")).containsExactly(summary);
        assertThat(service.context("user1")).contains("Markdown summary");
        assertThat(service.forget("user1", "s-1")).isTrue();
    }
}
