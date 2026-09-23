// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.server;

import java.time.Instant;

/** Metadata-only audit event. Business payloads and responses are deliberately excluded. */
public record McpServerAuditEvent(Instant at, String actor, String client, String method, String target,
                                  String outcome, long durationNanos) {
    public McpServerAuditEvent(Instant at, String actor, String method, String target,
                               String outcome, long durationNanos) {
        this(at, actor, null, method, target, outcome, durationNanos);
    }
}
