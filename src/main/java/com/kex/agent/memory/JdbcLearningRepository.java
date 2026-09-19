// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcLearningRepository implements LearningRepository {
    private final JdbcTemplate jdbc;
    private final int capacity;

    public JdbcLearningRepository(JdbcTemplate jdbc, int capacity) {
        this.jdbc = jdbc;
        this.capacity = Math.max(1, capacity);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS kex_agent_learning (
                  id VARCHAR(64) PRIMARY KEY, owner VARCHAR(255) NOT NULL,
                  kind VARCHAR(16) NOT NULL, title VARCHAR(200) NOT NULL,
                  markdown TEXT NOT NULL, evidence TEXT NOT NULL, conversation_id VARCHAR(255),
                  created_at TIMESTAMP NOT NULL, status VARCHAR(16) NOT NULL,
                  reviewed_by VARCHAR(255), reviewed_at TIMESTAMP, review_reason VARCHAR(2000)
                )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS kex_learning_owner_kind ON kex_agent_learning(owner, kind, created_at)");
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
}
