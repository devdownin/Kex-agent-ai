// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import org.springframework.ai.chat.model.ToolContext;

/** Trusted principal propagated by AgentService, never an argument supplied by the model. */
public final class MemoryIdentity {
    public static final String OWNER_CONTEXT_KEY = "kex.owner";
    private MemoryIdentity() { }

    public static String from(ToolContext context) {
        Object owner = context == null ? null : context.getContext().get(OWNER_CONTEXT_KEY);
        // Direct embedding/tests without an authenticated request retain the historical isolated
        // local namespace. HTTP agent requests always propagate their authenticated principal.
        return require(owner instanceof String value ? value : "kex-internal");
    }

    public static String require(String owner) {
        if (owner == null || owner.isBlank() || owner.length() > 255) {
            throw new IllegalArgumentException("Une identité authentifiée est requise pour la mémoire");
        }
        return owner;
    }
}
