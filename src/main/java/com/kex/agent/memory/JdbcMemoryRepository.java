// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Actif sous le profil {@code shared-memory}, où {@code JdbcTemplate} existe déjà pour la mémoire
 * de conversation et l'audit. {@code CREATE TABLE IF NOT EXISTS} à la construction, même raison
 * que {@code JdbcAuditRepository} : une seule ligne à retenir plutôt qu'un outil de migration pour
 * une table.
 */
class JdbcMemoryRepository implements MemoryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final int capacity;

    JdbcMemoryRepository(JdbcTemplate jdbcTemplate, int capacity) {
        this.jdbcTemplate = jdbcTemplate;
        this.capacity = capacity;
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS kex_agent_memory (
                  id VARCHAR(64) PRIMARY KEY,
                  content VARCHAR(2000) NOT NULL,
                  conversation_id VARCHAR(64),
                  created_at TIMESTAMP NOT NULL,
                  superseded_by VARCHAR(64),
                  owner VARCHAR(255) NOT NULL DEFAULT 'kex-internal'
                )""");
        jdbcTemplate.execute("ALTER TABLE kex_agent_memory ADD COLUMN IF NOT EXISTS owner VARCHAR(255) NOT NULL DEFAULT 'kex-internal'");
    }

    @Override
    public void add(MemoryEntry entry) {
        jdbcTemplate.update("""
                INSERT INTO kex_agent_memory (id, content, conversation_id, created_at, superseded_by, owner)
                VALUES (?, ?, ?, ?, ?, ?)""",
                entry.id(), entry.content(), entry.conversationId(), Timestamp.from(entry.createdAt()),
                entry.supersededBy(), entry.owner());
        // Une rétention filtrée à la lecture seule ne borne jamais la table : une instance qui
        // tourne des mois accumulerait des lignes qu'aucune fenêtre de lecture ne referait petites.
        jdbcTemplate.update("""
                DELETE FROM kex_agent_memory WHERE id NOT IN (
                  SELECT id FROM kex_agent_memory ORDER BY created_at DESC LIMIT ?)""",
                capacity);
    }

    @Override
    public boolean supersede(String id, String bySupersedingId) {
        // `superseded_by IS NULL` dans le WHERE : un souvenir déjà remplacé ne se remarque pas, et
        // l'appelant doit pouvoir distinguer « marqué » de « identifiant inconnu ou déjà périmé ».
        return jdbcTemplate.update("""
                UPDATE kex_agent_memory SET superseded_by = ?
                WHERE id = ? AND superseded_by IS NULL""", bySupersedingId, id) > 0;
    }

    @Override
    public List<MemoryEntry> active(Instant since) {
        return jdbcTemplate.query("""
                SELECT id, content, conversation_id, created_at, superseded_by, owner FROM kex_agent_memory
                WHERE created_at >= ? AND superseded_by IS NULL ORDER BY created_at DESC""",
                (rs, rowNum) -> new MemoryEntry(rs.getString("id"), rs.getString("content"),
                        rs.getString("conversation_id"), rs.getTimestamp("created_at").toInstant(),
                        rs.getString("superseded_by"), rs.getString("owner")),
                Timestamp.from(since));
    }

    @Override
    public Optional<MemoryEntry> forget(String id) {
        MemoryEntry entry;
        try {
            entry = jdbcTemplate.queryForObject("""
                    SELECT id, content, conversation_id, created_at, superseded_by, owner FROM kex_agent_memory
                    WHERE id = ?""",
                    (rs, rowNum) -> new MemoryEntry(rs.getString("id"), rs.getString("content"),
                            rs.getString("conversation_id"), rs.getTimestamp("created_at").toInstant(),
                            rs.getString("superseded_by"), rs.getString("owner")),
                    id);
        }
        catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
        jdbcTemplate.update("DELETE FROM kex_agent_memory WHERE id = ?", id);
        return Optional.of(entry);
    }
}
