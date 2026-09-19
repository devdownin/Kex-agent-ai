// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.util.Set;

import org.springframework.ai.chat.model.ToolContext;

/** An allowlist supplied by trusted code, never by the model or a task's prompt. */
public final class TaskToolPolicy {

    public static final String ALLOWED_TOOLS = "kex.allowed-tools";

    private TaskToolPolicy() { }

    public static void check(String name, ToolContext context) {
        if (context == null || !context.getContext().containsKey(ALLOWED_TOOLS)) {
            return;
        }
        Object allowed = context.getContext().get(ALLOWED_TOOLS);
        if (!(allowed instanceof Set<?> names) || !names.contains(name)) {
            throw new SecurityException("Outil interdit pour cette tâche : " + name);
        }
    }
}
