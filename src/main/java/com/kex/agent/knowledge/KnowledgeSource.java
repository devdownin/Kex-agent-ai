// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.knowledge;

/** The dated evidence actually supplied to the model, attached to the response and SSE stream. */
public record KnowledgeSource(String id, String source, String observedAt, String validUntil, String excerpt) {
    public static KnowledgeSource from(KnowledgeMatch match) {
        String text = match.text() == null ? "" : match.text();
        return new KnowledgeSource(match.id(), match.metadata().get("source").toString(),
                match.metadata().get("observedAt").toString(), match.metadata().get("validUntil").toString(),
                text.substring(0, Math.min(4000, text.length())));
    }
}
