// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.server;

import java.util.List;
import java.util.Map;

/**
 * Contract for future state-changing MCP operations.
 *
 * <p>Mutations are intentionally not registered by the transport yet. A request must name an
 * existing pending decision, carry an authenticated actor and pass the existing supervision
 * policy/approval path before execution. This type makes that boundary explicit without creating
 * a bypass around {@code SupervisionService.approve/reject}.
 */
public record GovernedMutationRequest(String decisionId, String reason) {

    public static final List<String> RESERVED_TOOL_NAMES =
            List.of("kex_approve_decision", "kex_reject_decision");

    public static final Map<String, Object> INPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "decisionId", Map.of("type", "string", "minLength", 1),
                    "reason", Map.of("type", "string")),
            "required", List.of("decisionId"),
            "additionalProperties", false);
}
