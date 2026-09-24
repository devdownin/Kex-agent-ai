// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.server;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** Tracks MCP client sessions for audit and read-only operational visibility. */
@Component
public class McpClientSessionRegistry {
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public String open(String name, String version) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        sessions.put(id, new Session(id, name, version, now, now, 0));
        return id;
    }

    public Client client(String sessionId) {
        Session session = sessionId == null ? null : sessions.get(sessionId);
        return session == null ? null : new Client(session.name(), session.version());
    }

    public void touch(String sessionId) {
        if (sessionId == null) return;
        sessions.computeIfPresent(sessionId, (id, session) ->
                new Session(id, session.name(), session.version(), session.createdAt(), Instant.now(), session.callCount() + 1));
    }

    public List<Session> snapshot() {
        return sessions.values().stream().sorted((a, b) -> b.lastActivityAt().compareTo(a.lastActivityAt())).toList();
    }

    public record Client(String name, String version) {
        public String label() { return name + "/" + version; }
    }

    public record Session(String id, String name, String version, Instant createdAt, Instant lastActivityAt, long callCount) {}
}
