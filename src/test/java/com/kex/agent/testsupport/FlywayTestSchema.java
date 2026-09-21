// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.testsupport;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;

/**
 * Un dépôt JDBC testé seul, contre son propre H2 en mémoire, ne passe plus par le contexte Spring
 * qui applique {@code db/migration/V1__baseline.sql} — la seule façon de lui donner un schéma est
 * donc de rejouer la même migration ici, pas une DDL réécrite à la main qui divergerait en
 * silence de celle que la production exécute réellement.
 */
public final class FlywayTestSchema {
    private FlywayTestSchema() { }

    public static void migrate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }
}
