// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Garde partagée avec les outils pour qu'un échange expiré ne déclenche plus d'effet. */
public final class AgentExecution {

    public static final String CONTEXT_KEY = "kex.execution";

    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void cancel() {
        cancelled.set(true);
    }

    public void ensureActive() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Échange annulé");
        }
    }

    public static void ensureActive(org.springframework.ai.chat.model.ToolContext context) {
        Object execution = context == null ? null : context.getContext().get(CONTEXT_KEY);
        if (execution instanceof AgentExecution active) {
            active.ensureActive();
        }
    }
}
