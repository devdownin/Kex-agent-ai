// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Le même seau à jetons que {@link TokenBucket}, mais dans une ligne que toutes les répliques
 * lisent. Sans lui, trois répliques derrière un load balancer accordaient trois fois le seuil
 * annoncé : la propriété disait soixante requêtes par minute et l'installation en laissait passer
 * cent quatre-vingt, sans que rien ne le signale.
 *
 * <p>Compare-and-set sur une colonne de révision plutôt qu'un {@code UPDATE} calculé en SQL :
 * le calcul de remplissage tient en Java, identique à celui du seau local, et reste lisible sur
 * n'importe quel moteur. Un tour de base par requête limitée — le chemin protégé appelle un modèle
 * de langage ou un serveur MCP juste après, la comparaison n'est pas serrée.
 *
 * <p>Un refus n'écrit rien : le remplissage se recalcule à la lecture suivante depuis la même
 * base de temps, et le budget n'a pas à être réécrit pour ne pas avoir été dépensé.
 *
 * <p>Le temps est celui de l'appelant, pas celui de la base : deux répliques dont les horloges
 * divergent se décalent d'autant sur le remplissage. Une seconde d'écart sur une minute de
 * fenêtre déplace le seuil de moins de deux pour cent, et un écart plus grand est déjà un
 * problème pour les horodatages d'audit. L'écoulement négatif — la réplique dont l'horloge est
 * en retard — est ramené à zéro plutôt que de retirer des jetons.
 */
class JdbcRateLimiter implements RateLimiter {

    private static final long SCALE = 1_000_000L;
    private static final long MILLIS_PER_MINUTE = Duration.ofMinutes(1).toMillis();

    /**
     * Au-delà, la contention vient de l'appelant lui-même — plusieurs requêtes concurrentes sous
     * le même principal, exactement ce que la limite existe pour freiner. Refuser plutôt que
     * réessayer sans fin : une boucle d'attente sur un compteur transformerait un dépassement de
     * débit en saturation du pool de connexions.
     */
    private static final int ATTEMPTS = 3;

    /** Au-delà, la ligne est pleine depuis longtemps : elle ne porte plus d'information. */
    private static final Duration STALE_AFTER = Duration.ofDays(1);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final long capacity;
    private final long refillPerMinute;
    private final long millisToFill;

    JdbcRateLimiter(JdbcTemplate jdbcTemplate, Clock clock, RateLimitProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.capacity = properties.burst() * SCALE;
        this.refillPerMinute = properties.requestsPerMinute() * SCALE;
        this.millisToFill = this.capacity * MILLIS_PER_MINUTE / this.refillPerMinute;
    }

    @Override
    public boolean tryConsume(String principal) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            Instant now = clock.instant();
            Bucket bucket = read(principal);
            if (bucket == null) {
                if (create(principal, now)) {
                    return true;
                }
                continue;
            }
            long available = refilled(bucket, now);
            if (available < SCALE) {
                return false;
            }
            int updated = jdbcTemplate.update("""
                    UPDATE kex_rate_limit SET tokens = ?, refilled_at = ?, revision = ?
                    WHERE principal = ? AND revision = ?""",
                    available - SCALE, Timestamp.from(now), bucket.revision() + 1, principal, bucket.revision());
            if (updated > 0) {
                return true;
            }
        }
        return false;
    }

    private long refilled(Bucket bucket, Instant now) {
        long elapsed = Math.max(0, Math.min(now.toEpochMilli() - bucket.refilledAt().toEpochMilli(), millisToFill));
        return Math.min(capacity, bucket.tokens() + elapsed * refillPerMinute / MILLIS_PER_MINUTE);
    }

    private Bucket read(String principal) {
        List<Bucket> rows = jdbcTemplate.query("""
                SELECT tokens, refilled_at, revision FROM kex_rate_limit WHERE principal = ?""",
                (rs, rowNum) -> new Bucket(rs.getLong("tokens"), rs.getTimestamp("refilled_at").toInstant(),
                        rs.getLong("revision")),
                principal);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** @return {@code false} si une autre réplique a créé la ligne entre-temps : à reprendre */
    private boolean create(String principal, Instant now) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO kex_rate_limit (principal, tokens, refilled_at, revision)
                    VALUES (?, ?, ?, 0)""", principal, capacity - SCALE, Timestamp.from(now));
        }
        catch (DuplicateKeyException concurrent) {
            return false;
        }
        // Sur ce chemin seulement, qui est le premier appel d'un appelant : une ligne dont le seau
        // a eu tout le temps de se remplir ne dit plus rien de son budget, et sous OIDC chaque
        // personne en crée une. Purgées ici plutôt que par une tâche de fond, qu'il faudrait
        // ordonnancer et verrouiller entre répliques pour supprimer quelques lignes.
        jdbcTemplate.update("DELETE FROM kex_rate_limit WHERE refilled_at < ?",
                Timestamp.from(now.minus(STALE_AFTER)));
        return true;
    }

    private record Bucket(long tokens, Instant refilledAt, long revision) {
    }
}
