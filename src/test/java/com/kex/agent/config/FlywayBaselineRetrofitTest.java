// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

import com.kex.agent.testsupport.FlywayTestSchema;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Ce que {@code baseline-version: 0} existe pour garantir : une base qui portait déjà une partie
 * du schéma — celle qui tournait sous l'ancien mécanisme, {@code CREATE TABLE IF NOT EXISTS} posé
 * à la construction de chaque dépôt — ne doit ni faire échouer la migration au démarrage, ni
 * rester avec les tables qui manquaient encore.
 *
 * <p>{@code baseline-version} au défaut de Flyway (1, la version de {@code V1__baseline.sql} elle-
 * même) romprait ceci : Flyway marquerait V1 « déjà appliquée » sans l'exécuter, et une table
 * absente de cette base précise — parce que son ancien dépôt n'avait pas encore tourné une fois
 * pour la créer — resterait absente pour de bon.
 */
class FlywayBaselineRetrofitTest {

    @Test
    void migre_une_base_qui_ne_porte_deja_qu_une_partie_du_schema() {
        DataSource dataSource = h2();
        // Simule une base qui tournait déjà sous l'ancien mécanisme : seul l'audit, le premier
        // dépôt jamais introduit, avait eu l'occasion de créer sa table. Non vide, non gérée par
        // Flyway — c'est exactement la condition qui déclenche le retrofit en base réelle.
        new JdbcTemplate(dataSource).execute("""
                CREATE TABLE kex_supervision_audit (
                  id VARCHAR(64) PRIMARY KEY,
                  occurred_at TIMESTAMP NOT NULL
                )""");

        assertThatCode(() -> FlywayTestSchema.migrate(dataSource)).doesNotThrowAnyException();

        // La table pré-existante n'a pas été recréée en écrasant sa structure...
        assertThat(columns(dataSource, "KEX_SUPERVISION_AUDIT")).containsExactly("ID", "OCCURRED_AT");
        // ...et une table qui n'existait pas encore sur cette base est bien apparue.
        assertThat(tableExists(dataSource, "KEX_RATE_LIMIT")).isTrue();
    }

    @Test
    void migre_une_base_entierement_neuve() {
        DataSource dataSource = h2();

        FlywayTestSchema.migrate(dataSource);

        assertThat(tableExists(dataSource, "KEX_SUPERVISION_AUDIT")).isTrue();
        assertThat(tableExists(dataSource, "KEX_RATE_LIMIT")).isTrue();
    }

    private static DataSource h2() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:flyway-retrofit-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static boolean tableExists(DataSource dataSource, String table) {
        Integer count = new JdbcTemplate(dataSource).queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?""",
                Integer.class, table);
        return count != null && count > 0;
    }

    private static List<String> columns(DataSource dataSource, String table) {
        return new JdbcTemplate(dataSource).queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = ? ORDER BY ordinal_position""", String.class, table);
    }
}
