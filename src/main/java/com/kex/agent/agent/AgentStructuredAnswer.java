// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.util.List;
import java.util.Map;

/** Mêmes métadonnées que {@link AgentAnswer}, pour la même raison. */
public record AgentStructuredAnswer(String conversationId, Map<String, Object> content,
                                    List<AgentEvent.ToolCall> tools, AgentUsage usage,
                                    String finishReason) {
}
