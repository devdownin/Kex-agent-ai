// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;

/** Schéma posé par {@code db/migration/V1__baseline.sql}, index compris. */
public final class JdbcLearningRepository implements LearningRepository {
    private final JdbcTemplate jdbc;
    private final int capacity;

    public JdbcLearningRepository(JdbcTemplate jdbc, int capacity) {
        this.jdbc = jdbc;
        this.capacity = Math.max(1, capacity);
    }

    @Override
    public void add(LearningEntry entry) {
        jdbc.update("""
                INSERT INTO kex_agent_learning (id, owner, kind, title, markdown, evidence, conversation_id,
                  created_at, status, reviewed_by, reviewed_at, review_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                entry.id(), entry.owner(), entry.kind(), entry.title(), entry.markdown(), entry.evidence(),
                entry.conversationId(), Timestamp.from(entry.createdAt()), entry.status(), entry.reviewedBy(),
                entry.reviewedAt() == null ? null : Timestamp.from(entry.reviewedAt()), entry.reviewReason());
        jdbc.update("""
                DELETE FROM kex_agent_learning WHERE kind = ? AND id NOT IN (
                  SELECT id FROM kex_agent_learning WHERE kind = ? ORDER BY created_at DESC, id DESC LIMIT ?)
                """, entry.kind(), entry.kind(), capacity);
    }

    @Override
    public List<LearningEntry> list(String owner, String kind, Instant since) {
        return jdbc.query("""
                SELECT * FROM kex_agent_learning WHERE owner = ? AND kind = ? AND created_at >= ?
                ORDER BY created_at DESC, id DESC""", (rs, row) -> {
                    Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
                    return new LearningEntry(rs.getString("id"), rs.getString("owner"), rs.getString("kind"),
                            rs.getString("title"), rs.getString("markdown"), rs.getString("evidence"),
                            rs.getString("conversation_id"), rs.getTimestamp("created_at").toInstant(),
                            rs.getString("status"), rs.getString("reviewed_by"),
                            reviewedAt == null ? null : reviewedAt.toInstant(), rs.getString("review_reason"));
                }, owner, kind, Timestamp.from(since));
    }

    @Override
    public boolean review(String owner, String id, String status, String actor, Instant at, String reason) {
        return jdbc.update("""
                UPDATE kex_agent_learning SET status = ?, reviewed_by = ?, reviewed_at = ?, review_reason = ?
                WHERE id = ? AND owner = ? AND kind = 'SKILL' AND status = 'PENDING'""",
                status, actor, Timestamp.from(at), reason, id, owner) == 1;
    }

    @Override
    public boolean delete(String owner, String id) {
        return jdbc.update("DELETE FROM kex_agent_learning WHERE id = ? AND owner = ?", id, owner) == 1;
    }

    @Override
    public Optional<String> ownerOf(String id) {
        return jdbc.query("SELECT owner FROM kex_agent_learning WHERE id = ?",
                (rs, row) -> rs.getString("owner"), id).stream().findFirst();
    }

    @Override
    public List<LearningEntry> pending(String kind) {
        return jdbc.query("""
                SELECT * FROM kex_agent_learning WHERE kind = ? AND status = 'PENDING'
                ORDER BY created_at ASC, id ASC""", (rs, row) -> {
                    Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
                    return new LearningEntry(rs.getString("id"), rs.getString("owner"), rs.getString("kind"),
                            rs.getString("title"), rs.getString("markdown"), rs.getString("evidence"),
                            rs.getString("conversation_id"), rs.getTimestamp("created_at").toInstant(),
                            rs.getString("status"), rs.getString("reviewed_by"),
                            reviewedAt == null ? null : reviewedAt.toInstant(), rs.getString("review_reason"));
                }, kind);
    }
}
