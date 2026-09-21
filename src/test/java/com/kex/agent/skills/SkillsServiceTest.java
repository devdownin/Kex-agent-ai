// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import com.kex.agent.memory.LearningEntry;
import com.kex.agent.memory.LearningRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SkillsServiceTest {

    private final LearningRepository repository = mock(LearningRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneId.of("UTC"));
    private SkillsService service;

    @BeforeEach
    void setUp() {
        service = new SkillsService(repository, clock);
    }

    @Test
    void proposes_skill_and_validates_inputs() {
        LearningEntry entry = service.propose("user1", "Check Lag", "Markdown steps", "Proof of execution", "conv-1");

        assertThat(entry.title()).isEqualTo("Check Lag");
        assertThat(entry.status()).isEqualTo("PENDING");
        verify(repository).add(entry);

        assertThatThrownBy(() -> service.propose("user1", "", "Markdown", "Evidence", "conv-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reviews_and_filters_approved_skills() {
        LearningEntry approved = new LearningEntry("s-1", "user1", "SKILL", "Title", "Markdown", "Evidence",
                "conv-1", clock.instant(), "APPROVED", "admin", clock.instant(), "Good procedure");

        // La revue ne suppose plus que l'appelant est le propriétaire : elle le demande au dépôt.
        given(repository.ownerOf("s-1")).willReturn(java.util.Optional.of("user1"));
        given(repository.review(eq("user1"), eq("s-1"), eq("APPROVED"), eq("admin"), eq(clock.instant()), eq("Good procedure")))
                .willReturn(true);
        given(repository.list("user1", "SKILL", Instant.EPOCH)).willReturn(List.of(approved));

        LearningEntry reviewed = service.review("s-1", true, "admin", "Good procedure");
        assertThat(reviewed.status()).isEqualTo("APPROVED");

        assertThat(service.approved("user1")).containsExactly(approved);
    }

    @Test
    void review_throws_on_unknown_or_already_reviewed_skill() {
        given(repository.ownerOf("s-99")).willReturn(java.util.Optional.of("user1"));
        given(repository.review(any(), any(), any(), any(), any(), any())).willReturn(false);

        assertThatThrownBy(() -> service.review("s-99", true, "admin", "Reason"))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Transverse : pas de propriétaire à passer, à la différence de {@code list}. */
    @Test
    void pending_reviews_lists_across_all_owners() {
        LearningEntry fromOpsConsole = new LearningEntry("s-1", "ops-console", "SKILL", "Title", "Markdown",
                "Evidence", "conv-1", clock.instant(), "PENDING", null, null, null);
        given(repository.pending("SKILL")).willReturn(List.of(fromOpsConsole));

        assertThat(service.pendingReviews()).containsExactly(fromOpsConsole);
    }
}
