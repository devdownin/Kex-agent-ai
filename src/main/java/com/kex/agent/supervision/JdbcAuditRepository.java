// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Actif sous le profil {@code shared-memory}, où {@code JdbcTemplate} existe déjà pour la mémoire
 * de conversation. {@code CREATE TABLE IF NOT EXISTS} à la construction, comme
 * {@code spring.ai.chat.memory.repository.jdbc.initialize-schema: always} pour la même raison :
 * une seule ligne à retenir plutôt qu'un outil de migration pour une table.
 *
 * <p>Une ligne par entrée, aucune mise à jour : l'audit ne se corrige pas, il s'accumule.
 */
class JdbcAuditRepository implements AuditRepository {

    private final JdbcTemplate jdbcTemplate;

    JdbcAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS kex_supervision_audit (
                  id VARCHAR(64) PRIMARY KEY,
                  occurred_at TIMESTAMP NOT NULL,
                  actor VARCHAR(255),
                  action VARCHAR(255),
                  process_id VARCHAR(255),
                  decision_id VARCHAR(64),
                  reason VARCHAR(2000),
                  policy_version VARCHAR(64),
                  result VARCHAR(2000),
                  correlation_id VARCHAR(64),
                  trace_id VARCHAR(64)
                )""");
    }

    @Override
    public void add(AuditEntry entry) {
        jdbcTemplate.update("""
                INSERT INTO kex_supervision_audit
                (id, occurred_at, actor, action, process_id, decision_id, reason, policy_version,
                 result, correlation_id, trace_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                entry.id(), Timestamp.from(entry.at()), entry.actor(), entry.action(), entry.processId(),
                entry.decisionId(), entry.reason(), entry.policyVersion(), entry.result(),
                entry.correlationId(), entry.traceId());
    }

    @Override
    public List<AuditEntry> recent(int limit) {
        return jdbcTemplate.query("""
                SELECT id, occurred_at, actor, action, process_id, decision_id, reason,
                       policy_version, result, correlation_id, trace_id
                FROM kex_supervision_audit ORDER BY occurred_at DESC LIMIT ?""",
                (rs, rowNum) -> new AuditEntry(rs.getString("id"), toInstant(rs.getTimestamp("occurred_at")),
                        rs.getString("actor"), rs.getString("action"), rs.getString("process_id"),
                        rs.getString("decision_id"), rs.getString("reason"), rs.getString("policy_version"),
                        rs.getString("result"), rs.getString("correlation_id"), rs.getString("trace_id")),
                limit);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
