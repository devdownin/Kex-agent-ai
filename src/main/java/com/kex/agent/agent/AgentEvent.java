// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

/** Ce qui peut sortir d'un échange en flux : du texte, ou un outil qui vient de s'exécuter. */
public sealed interface AgentEvent {

    record Token(String text) implements AgentEvent {
    }

    record ToolCall(String tool, long durationMillis, boolean failed) implements AgentEvent {
    }
}
