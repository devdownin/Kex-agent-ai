// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.server;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerRateLimiterTest {

    @Test
    void rejects_requests_after_actor_quota_is_consumed() {
        var properties = new KexMcpServerProperties(true, Set.of(), 2, false, java.time.Duration.ofMinutes(30), java.time.Duration.ofMinutes(5));
        var limiter = new McpServerRateLimiter(properties,
                Clock.fixed(Instant.parse("2026-09-23T06:00:00Z"), ZoneOffset.UTC));

        assertThat(limiter.allow("alice")).isTrue();
        assertThat(limiter.allow("alice")).isTrue();
        assertThat(limiter.allow("alice")).isFalse();
        assertThat(limiter.allow("bob")).isTrue();
    }
}
