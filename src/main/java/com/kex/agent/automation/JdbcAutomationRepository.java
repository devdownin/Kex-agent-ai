// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.automation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Atomic compare-and-set claims; advancing next_run before execution prevents occurrence replay. */
final class JdbcAutomationRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    JdbcAutomationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(new JdbcTransactionManager(jdbc.getDataSource()));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS kex_automation (
                  id VARCHAR(36) PRIMARY KEY,
                  owner VARCHAR(160) NOT NULL,
                  name VARCHAR(160) NOT NULL,
                  prompt TEXT NOT NULL,
                  cron VARCHAR(120) NOT NULL,
                  zone VARCHAR(80) NOT NULL,
                  enabled BOOLEAN NOT NULL,
                  next_run TIMESTAMP NOT NULL,
                  last_run TIMESTAMP,
                  status VARCHAR(32) NOT NULL,
                  result TEXT NOT NULL,
                  created_at TIMESTAMP NOT NULL,
                  updated_at TIMESTAMP NOT NULL,
                  locked_until TIMESTAMP NOT NULL,
                  claim_id VARCHAR(36) NOT NULL,
                  revision BIGINT NOT NULL
                )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS kex_automation_due ON kex_automation (enabled, next_run)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS kex_automation_audit (
                  id VARCHAR(36) PRIMARY KEY,
                  automation_id VARCHAR(36) NOT NULL,
                  owner VARCHAR(160) NOT NULL,
                  at TIMESTAMP NOT NULL,
                  actor VARCHAR(160) NOT NULL,
                  action VARCHAR(32) NOT NULL,
                  result TEXT NOT NULL
                )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS kex_automation_audit_owner ON kex_automation_audit (owner, at)");
    }

    Automation create(String owner, AutomationRequest request, Instant now, Instant next) {
        String id = UUID.randomUUID().toString();
        return transaction.execute(status -> {
            jdbc.update("""
                    INSERT INTO kex_automation (id, owner, name, prompt, cron, zone, enabled,
                      next_run, status, result, created_at, updated_at, locked_until, claim_id, revision)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'IDLE', '', ?, ?, ?, '', 0)
                    """, id, owner, request.name(), request.prompt(), request.cron(), request.zone(),
                    request.enabled(), ts(next), ts(now), ts(now), ts(Instant.EPOCH));
            audit(id, owner, owner, "CREATED", "", now);
            return get(owner, id);
        });
    }

    Automation get(String owner, String id) {
        return jdbc.query("SELECT * FROM kex_automation WHERE id = ? AND owner = ?",
                JdbcAutomationRepository::map, id, owner).stream().findFirst()
                .orElseThrow(UnknownAutomationException::new);
    }

    List<Automation> list(String owner) {
        return jdbc.query("SELECT * FROM kex_automation WHERE owner = ? ORDER BY created_at DESC LIMIT 500",
                JdbcAutomationRepository::map, owner);
    }

    Automation update(String owner, String id, AutomationRequest request, Instant now, Instant next) {
        return transaction.execute(status -> {
            get(owner, id);
            int changed = jdbc.update("""
                    UPDATE kex_automation SET name = ?, prompt = ?, cron = ?, zone = ?, enabled = ?,
                      next_run = ?, updated_at = ?, revision = revision + 1
                    WHERE id = ? AND owner = ? AND locked_until <= ?
                    """, request.name(), request.prompt(), request.cron(), request.zone(), request.enabled(),
                    ts(next), ts(now), id, owner, ts(now));
            if (changed != 1) {
                throw new IllegalStateException("Automatisation en cours d'exécution");
            }
            audit(id, owner, owner, "UPDATED", "", now);
            return get(owner, id);
        });
    }

    void delete(String owner, String id, Instant now) {
        transaction.executeWithoutResult(status -> {
            get(owner, id);
            if (jdbc.update("DELETE FROM kex_automation WHERE id = ? AND owner = ? AND locked_until <= ?",
                    id, owner, ts(now)) != 1) {
                throw new IllegalStateException("Automatisation en cours d'exécution");
            }
            audit(id, owner, owner, "DELETED", "", now);
        });
    }

    record Candidate(Automation task, long revision) {
    }

    record Claim(Automation task, String token) {
    }

    List<Candidate> due(Instant now, int limit) {
        return jdbc.query("""
                SELECT * FROM kex_automation
                WHERE enabled = TRUE AND next_run <= ? AND locked_until <= ?
                ORDER BY next_run, id LIMIT ?
                """, (rs, i) -> new Candidate(map(rs, i), rs.getLong("revision")), ts(now), ts(now), limit);
    }

    Optional<Claim> claim(Candidate candidate, Instant now, Duration lease) {
        Automation task = candidate.task();
        Instant next = new AutomationSchedule(task.cron(), task.zone()).next(now);
        String token = UUID.randomUUID().toString();
        return transaction.execute(status -> {
            int changed = jdbc.update("""
                    UPDATE kex_automation SET next_run = ?, last_run = ?, status = 'RUNNING', result = '',
                      claim_id = ?, locked_until = ?, revision = revision + 1
                    WHERE id = ? AND enabled = TRUE AND revision = ? AND next_run <= ? AND locked_until <= ?
                    """, ts(next), ts(now), token, ts(now.plus(lease)), task.id(), candidate.revision(),
                    ts(now), ts(now));
            if (changed == 0) {
                return Optional.empty();
            }
            audit(task.id(), task.owner(), "scheduler", "CLAIMED", token, now);
            return Optional.of(new Claim(task, token));
        });
    }

    void finish(Claim claim, String outcome, String result, Instant now) {
        transaction.executeWithoutResult(status -> {
            int changed = jdbc.update("""
                    UPDATE kex_automation SET status = ?, result = ?, locked_until = ?, claim_id = ''
                    WHERE id = ? AND claim_id = ?
                    """, outcome, result, ts(now), claim.task().id(), claim.token());
            if (changed == 1) {
                audit(claim.task().id(), claim.task().owner(), "scheduler", outcome, result, now);
            }
        });
    }

    List<AutomationAudit> audit(String owner) {
        return jdbc.query("SELECT * FROM kex_automation_audit WHERE owner = ? ORDER BY at DESC, id DESC LIMIT 100",
                (rs, i) -> new AutomationAudit(rs.getString("id"), rs.getString("automation_id"),
                        rs.getTimestamp("at").toInstant(), rs.getString("actor"), rs.getString("action"),
                        rs.getString("result")), owner);
    }

    private void audit(String id, String owner, String actor, String action, String result, Instant now) {
        jdbc.update("INSERT INTO kex_automation_audit (id, automation_id, owner, at, actor, action, result) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID().toString(), id, owner, ts(now), actor, action, result);
    }

    private static Automation map(ResultSet rs, int row) throws SQLException {
        Timestamp last = rs.getTimestamp("last_run");
        return new Automation(rs.getString("id"), rs.getString("owner"), rs.getString("name"),
                rs.getString("prompt"), rs.getString("cron"), rs.getString("zone"), rs.getBoolean("enabled"),
                rs.getTimestamp("next_run").toInstant(), last == null ? null : last.toInstant(),
                rs.getString("status"), rs.getString("result"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Timestamp ts(Instant at) {
        return Timestamp.from(at);
    }
}
