// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.server;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** Tracks MCP client sessions for audit and read-only operational visibility. */
@Component
public class McpClientSessionRegistry {
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;
    private final Duration idleAfter;

    public McpClientSessionRegistry(KexMcpServerProperties properties) {
        this(Clock.systemUTC(), properties.sessionTtl(), properties.sessionIdleAfter());
    }

    McpClientSessionRegistry(Clock clock, Duration ttl, Duration idleAfter) {
        this.clock = clock;
        this.ttl = ttl;
        this.idleAfter = idleAfter;
    }

    public String open(String name, String version, String protocol) {
        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        sessions.put(id, new Session(id, name, version, protocol, now, now, 0));
        return id;
    }

    public Client client(String sessionId) {
        purgeExpired();
        Session session = sessionId == null ? null : sessions.get(sessionId);
        return session == null ? null : new Client(session.name(), session.version());
    }

    public void touch(String sessionId) {
        if (sessionId == null) return;
        sessions.computeIfPresent(sessionId, (id, session) ->
                new Session(id, session.name(), session.version(), session.protocol(), session.createdAt(), clock.instant(), session.callCount() + 1));
    }

    public List<SessionView> snapshot() {
        purgeExpired();
        Instant now = clock.instant();
        return sessions.values().stream()
                .sorted((a, b) -> b.lastActivityAt().compareTo(a.lastActivityAt()))
                .map(session -> new SessionView(session.id(), session.name(), session.version(), session.protocol(),
                        session.createdAt(), session.lastActivityAt(), session.callCount(),
                        Duration.between(session.lastActivityAt(), now).compareTo(idleAfter) < 0 ? "ACTIVE" : "IDLE"))
                .toList();
    }

    private void purgeExpired() {
        Instant cutoff = clock.instant().minus(ttl);
        sessions.entrySet().removeIf(entry -> entry.getValue().lastActivityAt().isBefore(cutoff));
    }

    public record Client(String name, String version) {
        public String label() { return name + "/" + version; }
    }

    private record Session(String id, String name, String version, String protocol, Instant createdAt, Instant lastActivityAt, long callCount) {}

    public record SessionView(String id, String name, String version, String protocol, Instant createdAt,
                              Instant lastActivityAt, long callCount, String state) {}
}
