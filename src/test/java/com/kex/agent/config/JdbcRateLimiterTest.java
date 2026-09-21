// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;

import com.kex.agent.testsupport.FlywayTestSchema;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deux limiteurs sur la même base : deux répliques. Le défaut que cela corrige est silencieux —
 * la propriété annonçait soixante requêtes par minute, trois répliques en laissaient passer cent
 * quatre-vingt, et rien dans les journaux ne l'aurait dit.
 */
class JdbcRateLimiterTest {

    private final JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-21T10:00:00Z"));

    private static DataSource dataSource() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.h2.Driver.class);
        dataSource.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        FlywayTestSchema.migrate(dataSource);
        return dataSource;
    }

    private JdbcRateLimiter limiter(int burst, int perMinute) {
        return new JdbcRateLimiter(jdbcTemplate, clock, new RateLimitProperties(true, perMinute, burst));
    }

    @Test
    void deux_repliques_partagent_le_meme_budget() {
        JdbcRateLimiter premiere = limiter(3, 60);
        JdbcRateLimiter seconde = limiter(3, 60);

        assertThat(premiere.tryConsume("ops-console")).isTrue();
        assertThat(seconde.tryConsume("ops-console")).isTrue();
        assertThat(premiere.tryConsume("ops-console")).isTrue();
        // Le quatrième dépasse la pointe, quelle que soit la réplique qui le reçoit.
        assertThat(seconde.tryConsume("ops-console")).isFalse();
        assertThat(premiere.tryConsume("ops-console")).isFalse();
    }

    /** La raison d'être du découpage par appelant : une clé en boucle n'affame pas les autres. */
    @Test
    void un_appelant_epuise_n_affame_pas_les_autres() {
        JdbcRateLimiter limiter = limiter(2, 60);
        limiter.tryConsume("ci-pipeline");
        limiter.tryConsume("ci-pipeline");

        assertThat(limiter.tryConsume("ci-pipeline")).isFalse();
        assertThat(limiter.tryConsume("ops-console")).isTrue();
    }

    @Test
    void le_seau_se_remplit_avec_le_temps() {
        JdbcRateLimiter limiter = limiter(2, 60);
        limiter.tryConsume("ops-console");
        limiter.tryConsume("ops-console");
        assertThat(limiter.tryConsume("ops-console")).isFalse();

        clock.advance(Duration.ofSeconds(1));

        assertThat(limiter.tryConsume("ops-console")).isTrue();
    }

    @Test
    void le_remplissage_ne_depasse_jamais_la_pointe() {
        JdbcRateLimiter limiter = limiter(2, 60);
        limiter.tryConsume("ops-console");

        clock.advance(Duration.ofHours(3));

        assertThat(limiter.tryConsume("ops-console")).isTrue();
        assertThat(limiter.tryConsume("ops-console")).isTrue();
        assertThat(limiter.tryConsume("ops-console")).isFalse();
    }

    /**
     * L'horloge en retard d'une réplique ne doit pas retirer de jetons : un écoulement négatif
     * ramené à zéro, sinon une réplique désynchronisée viderait le budget des autres.
     */
    @Test
    void une_horloge_en_retard_ne_retire_pas_de_jetons() {
        JdbcRateLimiter limiter = limiter(2, 60);
        limiter.tryConsume("ops-console");

        clock.advance(Duration.ofMinutes(-5));

        assertThat(limiter.tryConsume("ops-console")).isTrue();
    }

    /** Un refus n'écrit rien : le budget non dépensé n'a pas à être réécrit. */
    @Test
    void un_refus_ne_modifie_pas_la_ligne() {
        JdbcRateLimiter limiter = limiter(1, 60);
        limiter.tryConsume("ops-console");
        Long revision = jdbcTemplate.queryForObject(
                "SELECT revision FROM kex_rate_limit WHERE principal = ?", Long.class, "ops-console");

        limiter.tryConsume("ops-console");

        assertThat(jdbcTemplate.queryForObject("SELECT revision FROM kex_rate_limit WHERE principal = ?",
                Long.class, "ops-console")).isEqualTo(revision);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
