// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.automation;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutomationScheduleTest {

    @Test
    void calculates_next_execution_time() {
        AutomationSchedule schedule = new AutomationSchedule("0 0 * * * *", "UTC");
        Instant now = Instant.parse("2026-09-20T10:15:00Z");
        Instant next = schedule.next(now);

        assertThat(next).isEqualTo(Instant.parse("2026-09-20T11:00:00Z"));
    }

    @Test
    void throws_on_invalid_cron_or_zone() {
        assertThatThrownBy(() -> new AutomationSchedule("invalid cron", "UTC"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new AutomationSchedule("0 0 * * * *", "Invalid/Zone"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
