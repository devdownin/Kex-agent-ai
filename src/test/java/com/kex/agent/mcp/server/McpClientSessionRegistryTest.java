// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.server;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpClientSessionRegistryTest {
    @Test
    void transitions_from_active_to_idle_then_expires_and_counts_calls() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-24T10:00:00Z"));
        var registry = new McpClientSessionRegistry(clock, Duration.ofMinutes(30), Duration.ofMinutes(5));

        String id = registry.open("client", "1", "2025-06-18");
        registry.touch(id);
        assertThat(registry.snapshot()).singleElement().satisfies(session -> {
            assertThat(session.state()).isEqualTo("ACTIVE");
            assertThat(session.callCount()).isEqualTo(1);
            assertThat(session.protocol()).isEqualTo("2025-06-18");
        });

        clock.advance(Duration.ofMinutes(5));
        assertThat(registry.snapshot()).singleElement().extracting(McpClientSessionRegistry.SessionView::state).isEqualTo("IDLE");

        clock.advance(Duration.ofMinutes(26));
        assertThat(registry.snapshot()).isEmpty();
        assertThat(registry.client(id)).isNull();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
