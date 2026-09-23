// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.server;

import java.time.Instant;

/**
 * Metadata-only audit event for inbound MCP operations. Payloads and business responses are
 * deliberately excluded so observability cannot become a second store for supervised data.
 */
public record McpServerAuditEvent(Instant at, String actor, String method, String target,
                                  String outcome, long durationNanos) {
}
