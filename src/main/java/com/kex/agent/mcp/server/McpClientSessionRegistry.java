// SPDX-License-Identifier: GPL-3.0-or-later
package com.kex.agent.mcp.server;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** Binds MCP session identifiers to the client identity declared during initialize. */
@Component
public class McpClientSessionRegistry {
    private final ConcurrentHashMap<String, Client> sessions = new ConcurrentHashMap<>();

    public String open(String name, String version) {
        String id = UUID.randomUUID().toString();
        sessions.put(id, new Client(name, version));
        return id;
    }

    public Client client(String sessionId) {
        return sessionId == null ? null : sessions.get(sessionId);
    }

    public record Client(String name, String version) {
        public String label() {
            return name + "/" + version;
        }
    }
}
