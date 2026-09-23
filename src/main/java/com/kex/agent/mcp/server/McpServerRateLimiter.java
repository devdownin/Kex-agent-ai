// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.server;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Fixed one-minute quota per authenticated actor; bounded counters are reset lazily each minute. */
@Component
public class McpServerRateLimiter {

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final KexMcpServerProperties properties;
    private final Clock clock;

    @Autowired
    public McpServerRateLimiter(KexMcpServerProperties properties) {
        this(properties, Clock.systemUTC());
    }

    McpServerRateLimiter(KexMcpServerProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean allow(String actor) {
        long minute = Instant.now(clock).getEpochSecond() / 60;
        Window window = windows.compute(actor, (key, current) ->
                current == null || current.minute() != minute ? new Window(minute, new AtomicInteger()) : current);
        return window.count().incrementAndGet() <= properties.requestsPerMinute();
    }

    private record Window(long minute, AtomicInteger count) {
    }
}
