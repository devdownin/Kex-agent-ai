// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Départ autonome du cycle. Un verrou en base, pas {@code @Scheduled} nu sur {@link
 * SupervisionService#runCycle} : en multi-instance, chaque réplique porterait le sien et les
 * actions partiraient en double — la règle documentée qui écartait tout ordonnancement jusqu'ici.
 *
 * <p>Une ligne à jour d'expiration, pas {@code pg_advisory_lock} : un verrou consultatif Postgres
 * s'attache à la connexion qui l'a pris, or {@code JdbcTemplate} en emprunte une par appel à un pool
 * — rien ne garantit que l'appel qui relâche tienne la même connexion que celui qui a pris. Une ligne
 * dont l'expiration se lit et s'écrit par une seule requête n'a pas ce problème, et une réplique qui
 * tombe pendant un cycle libère le verrou de lui-même à l'expiration plutôt que de le garder à jamais.
 */
class SupervisionScheduler {

    private static final Logger log = LoggerFactory.getLogger(SupervisionScheduler.class);

    private static final String LOCK_NAME = "supervision-cycle";

    private final SupervisionService supervision;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final Duration lockAtMostFor;

    /** Une par instance de processus : distingue dans les journaux qui a tenu le verrou. */
    private final String owner = UUID.randomUUID().toString();

    SupervisionScheduler(SupervisionService supervision, JdbcTemplate jdbcTemplate, Clock clock,
                        Duration lockAtMostFor) {
        this.supervision = supervision;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.lockAtMostFor = lockAtMostFor;
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS kex_supervision_lock (
                  name VARCHAR(64) PRIMARY KEY,
                  locked_until TIMESTAMP NOT NULL,
                  locked_by VARCHAR(64) NOT NULL
                )""");
        jdbcTemplate.update("""
                INSERT INTO kex_supervision_lock (name, locked_until, locked_by)
                SELECT ?, ?, '' WHERE NOT EXISTS (SELECT 1 FROM kex_supervision_lock WHERE name = ?)""",
                LOCK_NAME, Timestamp.from(Instant.EPOCH), LOCK_NAME);
    }

    @Scheduled(fixedDelayString = "${kex.agent.supervision.schedule.interval:5m}")
    void tick() {
        if (!acquire()) {
            return;
        }
        try {
            supervision.runCycle("scheduler");
        }
        catch (RuntimeException ex) {
            // En pause, cycle déjà en cours ailleurs dans cette même instance : rien d'anormal,
            // la prochaine tentative referra l'état des lieux.
            log.info("Cycle planifié non exécuté : {}", ex.getMessage());
        }
        finally {
            release();
        }
    }

    private boolean acquire() {
        Instant now = clock.instant();
        int rows = jdbcTemplate.update(
                "UPDATE kex_supervision_lock SET locked_until = ?, locked_by = ? WHERE name = ? AND locked_until < ?",
                Timestamp.from(now.plus(lockAtMostFor)), owner, LOCK_NAME, Timestamp.from(now));
        return rows == 1;
    }

    /**
     * Relâché tout de suite après le cycle plutôt que d'attendre {@code lockAtMostFor} : sans quoi
     * une autre réplique attendrait ce plafond avant de pouvoir retenter, même quand tout s'est bien
     * passé. Conditionné à {@code locked_by = owner} : une instance qui a dépassé le plafond et
     * perdu le verrou ne doit pas relâcher celui qu'une autre a repris entre-temps.
     */
    private void release() {
        jdbcTemplate.update("UPDATE kex_supervision_lock SET locked_until = ? WHERE name = ? AND locked_by = ?",
                Timestamp.from(clock.instant()), LOCK_NAME, owner);
    }
}
