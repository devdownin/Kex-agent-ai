// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.util.List;

/** @param tools les outils réellement exécutés pour produire cette réponse, dans l'ordre */
public record AgentAnswer(String conversationId, String content, List<AgentEvent.ToolCall> tools) {
}
