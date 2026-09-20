// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.automation;

import java.security.Principal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AutomationControllerTest {

    private final AutomationService service = mock(AutomationService.class);
    private final Principal principal = () -> "user1";
    private AutomationController controller;

    @BeforeEach
    void setUp() {
        controller = new AutomationController(service);
    }

    @Test
    void controller_delegates_all_endpoints() {
        Automation task = new Automation("a-1", "user1", "task", "prompt", "0 * * * * *", "UTC", true,
                Instant.now(), null, "IDLE", "", Instant.now(), Instant.now());
        AutomationRequest req = new AutomationRequest("task", "prompt", "0 * * * * *", "UTC", true);
        AutomationAudit audit = new AutomationAudit("au-1", "a-1", Instant.now(), "user1", "CREATED", "");

        given(service.list("user1")).willReturn(List.of(task));
        given(service.audit("user1")).willReturn(List.of(audit));
        given(service.create("user1", req)).willReturn(task);
        given(service.update("user1", "a-1", req)).willReturn(task);

        assertThat(controller.list(principal)).containsExactly(task);
        assertThat(controller.audit(principal)).containsExactly(audit);
        assertThat(controller.create(principal, req)).isEqualTo(task);
        assertThat(controller.update(principal, "a-1", req)).isEqualTo(task);

        controller.delete(principal, "a-1");
        verify(service).delete("user1", "a-1");
    }
}
