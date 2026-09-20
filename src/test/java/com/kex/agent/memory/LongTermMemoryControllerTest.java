// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.security.Principal;
import java.time.Instant;
import java.util.List;

import com.kex.agent.supervision.SupervisionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LongTermMemoryControllerTest {

    private final LongTermMemoryService memory = mock(LongTermMemoryService.class);
    private final SupervisionService supervision = mock(SupervisionService.class);
    private final Principal principal = () -> "user1";
    private LongTermMemoryController controller;

    @BeforeEach
    void setUp() {
        controller = new LongTermMemoryController(memory, supervision);
    }

    @Test
    void lists_summaries_for_authenticated_actor() {
        LearningEntry summary = new LearningEntry("s-1", "user1", "SUMMARY", "Title", "Summary markdown",
                "Evidence", "conv-1", Instant.now(), "READY", null, null, null);

        given(memory.summaries("user1")).willReturn(List.of(summary));

        List<LearningEntry> result = controller.list(principal);
        assertThat(result).containsExactly(summary);
    }

    @Test
    void forgets_summary_and_audits() {
        given(memory.forget("user1", "s-1")).willReturn(true);

        controller.forget("s-1", principal);

        verify(supervision).auditAction("user1", "Mémoire durable supprimée", "s-1");
    }

    @Test
    void throws_404_when_summary_to_forget_is_not_found() {
        given(memory.forget("user1", "s-unknown")).willReturn(false);

        assertThatThrownBy(() -> controller.forget("s-unknown", principal))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
