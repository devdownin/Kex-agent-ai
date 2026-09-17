// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Une ligne à jour d'expiration, pas {@code pg_advisory_lock} — voir la javadoc de {@link
 * SupervisionScheduler}. Ce qui est vérifié ici est exactement ce que cette ligne doit garantir :
 * un verrou tenu par une autre instance bloque, son expiration libère, et un cycle relâche le
 * verrou dès sa fin plutôt que d'attendre le plafond.
 */
class SupervisionSchedulerTest {

    private static final String LOCK_QUERY =
            "SELECT locked_until, locked_by FROM kex_supervision_lock WHERE name = 'supervision-cycle'";

    private JdbcTemplate jdbcTemplate;
    private SupervisionService supervision;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-17T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:supervision-lock-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbcTemplate = new JdbcTemplate((DataSource) dataSource);
        supervision = mock(SupervisionService.class);
    }

    private SupervisionScheduler scheduler(SupervisionService supervisionService) {
        return new SupervisionScheduler(supervisionService, jdbcTemplate, clock, Duration.ofMinutes(10));
    }

    @Test
    void lance_un_cycle_quand_le_verrou_est_libre() {
        scheduler(supervision).tick();

        verify(supervision).runCycle("scheduler");
    }

    @Test
    void ne_lance_pas_de_cycle_si_une_autre_replique_tient_deja_le_verrou() {
        scheduler(mock(SupervisionService.class)); // crée la table et sa ligne de départ
        jdbcTemplate.update("UPDATE kex_supervision_lock SET locked_until = ?, locked_by = 'autre-instance' "
                        + "WHERE name = 'supervision-cycle'",
                Timestamp.from(clock.instant().plus(Duration.ofMinutes(5))));

        scheduler(supervision).tick();

        verify(supervision, never()).runCycle(anyString());
    }

    @Test
    void reprend_une_fois_le_verrou_de_l_autre_replique_expire() {
        scheduler(mock(SupervisionService.class));
        jdbcTemplate.update("UPDATE kex_supervision_lock SET locked_until = ?, locked_by = 'autre-instance' "
                        + "WHERE name = 'supervision-cycle'",
                Timestamp.from(clock.instant().minus(Duration.ofSeconds(1))));

        scheduler(supervision).tick();

        verify(supervision).runCycle("scheduler");
    }

    @Test
    void relache_le_verrou_apres_le_cycle_plutot_que_d_attendre_le_plafond() {
        scheduler(supervision).tick();

        Timestamp lockedUntil = jdbcTemplate.queryForObject(LOCK_QUERY, (rs, i) -> rs.getTimestamp("locked_until"));
        // Sans ce relâchement immédiat, la prochaine réplique attendrait lockAtMostFor (10 min)
        // avant de pouvoir retenter, même quand le cycle s'est bien terminé.
        assertThat(lockedUntil.toInstant()).isBeforeOrEqualTo(clock.instant());
    }

    @Test
    void relache_le_verrou_meme_si_le_cycle_leve() {
        given(supervision.runCycle("scheduler")).willThrow(new AgentPausedException());

        assertThatCode(() -> scheduler(supervision).tick()).doesNotThrowAnyException();

        Timestamp lockedUntil = jdbcTemplate.queryForObject(LOCK_QUERY, (rs, i) -> rs.getTimestamp("locked_until"));
        assertThat(lockedUntil.toInstant()).isBeforeOrEqualTo(clock.instant());
    }
}
