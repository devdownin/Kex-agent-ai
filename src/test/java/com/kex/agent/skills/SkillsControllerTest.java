// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.security.Principal;
import java.time.Instant;
import java.util.List;

import com.kex.agent.memory.LearningEntry;
import com.kex.agent.supervision.SupervisionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SkillsControllerTest {

    private final SkillsService service = mock(SkillsService.class);
    private final SkillCurator curator = mock(SkillCurator.class);
    private final SupervisionService supervision = mock(SupervisionService.class);
    private final Principal principal = () -> "admin";
    private SkillsController controller;

    @BeforeEach
    void setUp() {
        controller = new SkillsController(service, curator, supervision);
    }

    @Test
    void controller_delegates_propose_approve_and_reject() {
        LearningEntry entry = new LearningEntry("s-1", "admin", "SKILL", "Check Lag", "Markdown", "Evidence",
                "conv-1", Instant.now(), "PENDING", null, null, null);
        LearningEntry approved = new LearningEntry("s-1", "admin", "SKILL", "Check Lag", "Markdown", "Evidence",
                "conv-1", Instant.now(), "APPROVED", "admin", Instant.now(), "Good");

        given(service.list("admin")).willReturn(List.of(entry));
        given(service.propose("admin", "Check Lag", "Markdown", "Evidence", "conv-1")).willReturn(entry);
        given(service.review("admin", "s-1", true, "admin", "Good")).willReturn(approved);
        given(service.review("admin", "s-1", false, "admin", "Bad")).willReturn(entry);

        assertThat(controller.list(principal)).containsExactly(entry);

        SkillsController.Proposal proposal = new SkillsController.Proposal("Check Lag", "Markdown", "Evidence", "conv-1");
        assertThat(controller.propose(proposal, principal)).isEqualTo(entry);
        verify(supervision).auditAction("admin", "Compétence proposée", "s-1");

        SkillsController.Review reviewApprove = new SkillsController.Review("Good");
        assertThat(controller.approve("s-1", reviewApprove, principal)).isEqualTo(approved);
        verify(supervision).auditAction("admin", "Compétence approuvée", "s-1 : Good");

        SkillsController.Review reviewReject = new SkillsController.Review("Bad");
        assertThat(controller.reject("s-1", reviewReject, principal)).isEqualTo(entry);
        verify(supervision).auditAction("admin", "Compétence rejetée", "s-1 : Bad");
    }
}
