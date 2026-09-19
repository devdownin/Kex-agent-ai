// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Instant;

/** Markdown content stays inert data until a named human approves a SKILL. */
public record LearningEntry(String id, String owner, String kind, String title, String markdown,
                            String evidence, String conversationId, Instant createdAt,
                            String status, String reviewedBy, Instant reviewedAt, String reviewReason) {
    public LearningEntry reviewed(String status, String actor, Instant at, String reason) {
        return new LearningEntry(id, owner, kind, title, markdown, evidence, conversationId, createdAt,
                status, actor, at, reason);
    }
}
