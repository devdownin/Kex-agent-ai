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
        return new SupervisionScheduler(supervisionService, jdbcTemplate, clock, Duration.ofMinutes(10),
                new SupervisionProperties.Adaptive(false, Duration.ofMinutes(5), Duration.ofMinutes(30)));
    }

    private SupervisionScheduler adaptiveScheduler() {
        return new SupervisionScheduler(supervision, jdbcTemplate, clock, Duration.ofMinutes(10),
                new SupervisionProperties.Adaptive(true, Duration.ofMinutes(5), Duration.ofMinutes(30)));
    }

    /**
     * Le battement reste régulier ; c'est la décision d'analyser qui suit l'état. Sans cycle connu,
     * l'état est {@code UNKNOWN} — précisément ce qui justifie d'aller regarder.
     */
    @Test
    void sans_cycle_connu_la_cadence_adaptative_lance_quand_meme() {
        given(supervision.cycles()).willReturn(java.util.List.of());

        adaptiveScheduler().tick();

        verify(supervision).runCycle("scheduler");
    }

    @Test
    void au_repos_la_cadence_adaptative_espace_les_cycles() {
        given(supervision.cycles()).willReturn(java.util.List.of(
                cycle(Instant.parse("2026-09-17T09:50:00Z"), 0, null)));

        adaptiveScheduler().tick();

        verify(supervision, never()).runCycle(anyString());
    }

    @Test
    void une_anomalie_au_dernier_cycle_resserre_la_cadence() {
        given(supervision.cycles()).willReturn(java.util.List.of(
                cycle(Instant.parse("2026-09-17T09:50:00Z"), 2, null)));

        adaptiveScheduler().tick();

        verify(supervision).runCycle("scheduler");
    }

    /** Un cycle en échec n'affirme rien sur le cluster : c'est la cadence resserrée qui s'applique. */
    @Test
    void un_cycle_en_echec_resserre_la_cadence_comme_une_anomalie() {
        given(supervision.cycles()).willReturn(java.util.List.of(
                cycle(Instant.parse("2026-09-17T09:50:00Z"), 0, "modèle injoignable")));

        adaptiveScheduler().tick();

        verify(supervision).runCycle("scheduler");
    }

    /** Un battement qui s'abstient ne prend pas le verrou : les autres répliques restent libres. */
    @Test
    void un_battement_qui_s_abstient_laisse_le_verrou_libre() {
        given(supervision.cycles()).willReturn(java.util.List.of(
                cycle(Instant.parse("2026-09-17T09:50:00Z"), 0, null)));

        adaptiveScheduler().tick();

        Timestamp lockedUntil = jdbcTemplate.queryForObject(LOCK_QUERY,
                (rs, row) -> rs.getTimestamp("locked_until"));
        assertThat(lockedUntil).isEqualTo(Timestamp.from(Instant.EPOCH));
    }

    private static CycleReport cycle(Instant finishedAt, int anomalies, String failure) {
        return new CycleReport("c-1", finishedAt.minus(Duration.ofSeconds(30)), finishedAt, 1,
                anomalies, 0, 0, java.util.List.of(), failure);
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
