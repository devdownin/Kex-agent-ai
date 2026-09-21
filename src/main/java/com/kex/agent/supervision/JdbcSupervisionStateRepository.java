// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Actif sous {@code shared-memory}, comme {@code JdbcAuditRepository} et {@code JdbcMemoryRepository},
 * et pour la même raison : c'est le seul profil où un {@code JdbcTemplate} existe.
 * {@code CREATE TABLE IF NOT EXISTS} à la construction plutôt qu'un outil de migration.
 *
 * <p>La décision voyage en JSON dans une colonne, avec son identifiant, son statut et sa date en
 * colonnes propres. Une {@link Decision} porte ses observations imbriquées : les éclater en
 * colonnes demanderait une seconde table et une jointure pour une donnée que rien n'interroge
 * autrement que par décision entière. Le statut, lui, est sorti : c'est le seul champ sur lequel
 * on filtre.
 */
class JdbcSupervisionStateRepository implements SupervisionStateRepository {

    private static final String PAUSED = "paused";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int historySize;

    JdbcSupervisionStateRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, Clock clock,
                                   int historySize) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.historySize = historySize;
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS kex_supervision_decision (
                  id VARCHAR(64) PRIMARY KEY,
                  decided_at TIMESTAMP NOT NULL,
                  status VARCHAR(32) NOT NULL,
                  payload TEXT NOT NULL,
                  claimed_at TIMESTAMP
                )""");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS kex_supervision_maintenance (
                  process_id VARCHAR(255) PRIMARY KEY,
                  process_name VARCHAR(255),
                  until_at TIMESTAMP NOT NULL,
                  reason VARCHAR(2000),
                  declared_by VARCHAR(255)
                )""");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS kex_supervision_flag (
                  name VARCHAR(64) PRIMARY KEY,
                  enabled BOOLEAN NOT NULL,
                  updated_at TIMESTAMP NOT NULL
                )""");
    }

    /* ── Pause ─────────────────────────────────────────────────────────── */

    @Override
    public boolean paused() {
        List<Boolean> rows = jdbcTemplate.query("SELECT enabled FROM kex_supervision_flag WHERE name = ?",
                (rs, rowNum) -> rs.getBoolean("enabled"), PAUSED);
        // Absence de ligne : jamais mis en pause. L'agent tourne, c'est son état d'installation.
        return !rows.isEmpty() && rows.getFirst();
    }

    @Override
    public void paused(boolean paused) {
        upsert("""
                UPDATE kex_supervision_flag SET enabled = ?, updated_at = ? WHERE name = ?""",
                """
                INSERT INTO kex_supervision_flag (name, enabled, updated_at) VALUES (?, ?, ?)""",
                new Object[] {paused, Timestamp.from(clock.instant()), PAUSED},
                new Object[] {PAUSED, paused, Timestamp.from(clock.instant())});
    }

    /* ── Maintenance ───────────────────────────────────────────────────── */

    @Override
    public void putMaintenance(MaintenanceWindow window) {
        upsert("""
                UPDATE kex_supervision_maintenance
                SET process_name = ?, until_at = ?, reason = ?, declared_by = ? WHERE process_id = ?""",
                """
                INSERT INTO kex_supervision_maintenance
                (process_id, process_name, until_at, reason, declared_by) VALUES (?, ?, ?, ?, ?)""",
                new Object[] {window.processName(), Timestamp.from(window.until()), window.reason(),
                        window.declaredBy(), window.processId()},
                new Object[] {window.processId(), window.processName(), Timestamp.from(window.until()),
                        window.reason(), window.declaredBy()});
    }

    @Override
    public MaintenanceWindow removeMaintenance(String processId) {
        MaintenanceWindow window = maintenance(processId);
        if (window != null) {
            jdbcTemplate.update("DELETE FROM kex_supervision_maintenance WHERE process_id = ?", processId);
        }
        return window;
    }

    @Override
    public List<MaintenanceWindow> activeMaintenance(Instant now) {
        jdbcTemplate.update("DELETE FROM kex_supervision_maintenance WHERE until_at <= ?", Timestamp.from(now));
        return jdbcTemplate.query("""
                SELECT process_id, process_name, until_at, reason, declared_by
                FROM kex_supervision_maintenance ORDER BY until_at""", MAINTENANCE);
    }

    private MaintenanceWindow maintenance(String processId) {
        return jdbcTemplate.query("""
                SELECT process_id, process_name, until_at, reason, declared_by
                FROM kex_supervision_maintenance WHERE process_id = ?""", MAINTENANCE, processId)
                .stream().findFirst().orElse(null);
    }

    /* ── Décisions ─────────────────────────────────────────────────────── */

    @Override
    public void store(Decision decision) {
        String payload = serialize(decision);
        upsert("UPDATE kex_supervision_decision SET decided_at = ?, status = ?, payload = ? WHERE id = ?",
                "INSERT INTO kex_supervision_decision (id, decided_at, status, payload) VALUES (?, ?, ?, ?)",
                new Object[] {Timestamp.from(decision.decidedAt()), decision.status().name(), payload,
                        decision.id()},
                new Object[] {decision.id(), Timestamp.from(decision.decidedAt()),
                        decision.status().name(), payload});
        // Même raison que pour la mémoire : une fenêtre de lecture bornée ne borne pas la table.
        jdbcTemplate.update("""
                DELETE FROM kex_supervision_decision WHERE id NOT IN (
                  SELECT id FROM kex_supervision_decision ORDER BY decided_at DESC, id DESC LIMIT ?)""",
                historySize);
    }

    @Override
    public Optional<Decision> decision(String id) {
        return jdbcTemplate.query("SELECT payload FROM kex_supervision_decision WHERE id = ?",
                (rs, rowNum) -> deserialize(rs.getString("payload")), id).stream().findFirst();
    }

    /**
     * {@code WHERE claimed_at IS NULL} fait toute la garantie : c'est la base qui tranche la
     * course, en un seul ordre, là où lire puis écrire en laisserait toujours une.
     */
    @Override
    public boolean claim(String id) {
        return jdbcTemplate.update("""
                UPDATE kex_supervision_decision SET claimed_at = ?
                WHERE id = ? AND claimed_at IS NULL""",
                Timestamp.from(clock.instant()), id) > 0;
    }

    @Override
    public List<Decision> decisions() {
        // `id` en second critère : deux décisions du même cycle partagent souvent la milliseconde,
        // et un ordre qui dépend alors du plan d'exécution rend une liste différente à chaque appel.
        return jdbcTemplate.query("""
                SELECT payload FROM kex_supervision_decision
                ORDER BY decided_at DESC, id DESC LIMIT ?""",
                (rs, rowNum) -> deserialize(rs.getString("payload")), historySize);
    }

    /* ── Outils ────────────────────────────────────────────────────────── */

    private static final org.springframework.jdbc.core.RowMapper<MaintenanceWindow> MAINTENANCE =
            (rs, rowNum) -> new MaintenanceWindow(rs.getString("process_id"), rs.getString("process_name"),
                    rs.getTimestamp("until_at").toInstant(), rs.getString("reason"),
                    rs.getString("declared_by"));

    /**
     * {@code UPDATE} puis {@code INSERT} si rien n'a bougé : {@code MERGE} et {@code ON CONFLICT}
     * ne s'écrivent pas pareil selon le moteur, et ces tables comptent au plus quelques lignes.
     *
     * <p>Deux répliques peuvent insérer la même clé au même instant — c'est précisément le cas que
     * ce dépôt existe pour servir. La clé primaire tranche, et le perdant reprend sur l'{@code
     * UPDATE} : sans cette reprise, déclarer une maintenance depuis deux écrans à la même seconde
     * rendrait une erreur pour une opération qui a pourtant abouti.
     */
    private void upsert(String update, String insert, Object[] updateArguments, Object[] insertArguments) {
        if (jdbcTemplate.update(update, updateArguments) > 0) {
            return;
        }
        try {
            jdbcTemplate.update(insert, insertArguments);
        }
        catch (DuplicateKeyException concurrent) {
            jdbcTemplate.update(update, updateArguments);
        }
    }

    private String serialize(Decision decision) {
        try {
            return objectMapper.writeValueAsString(decision);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Décision non sérialisable : " + decision.id(), ex);
        }
    }

    private Decision deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, Decision.class);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Décision illisible en base", ex);
        }
    }
}
