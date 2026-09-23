// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.server;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Publishes MCP audit metadata for persistence, SIEM or tracing listeners without coupling transport to them. */
@Component
public class McpServerAuditPublisher {

    private final ApplicationEventPublisher events;

    public McpServerAuditPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    public void publish(McpServerAuditEvent event) {
        events.publishEvent(event);
    }
}
