// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sur H2 plutôt qu'un contexte Spring complet : ce qui est en jeu est le SQL, le mappage d'une
 * ligne et la borne de capacité, pas le câblage du profil {@code shared-memory}.
 */
class JdbcMemoryRepositoryTest {

    private static DataSource dataSource() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.h2.Driver.class);
        dataSource.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return dataSource;
    }

    @Test
    void persiste_et_relit_du_plus_recent_au_plus_ancien() {
        JdbcMemoryRepository repository = new JdbcMemoryRepository(new JdbcTemplate(dataSource()), 200);
        Instant first = Instant.parse("2026-09-15T05:00:00Z");
        repository.add(new MemoryEntry("m1", "premier fait", "conv-1", first));
        repository.add(new MemoryEntry("m2", "second fait", "conv-1", first.plusSeconds(5)));

        List<MemoryEntry> active = repository.active(first.minusSeconds(1));

        assertThat(active).extracting(MemoryEntry::id).containsExactly("m2", "m1");
    }

    @Test
    void filtre_les_entrees_perimees() {
        JdbcMemoryRepository repository = new JdbcMemoryRepository(new JdbcTemplate(dataSource()), 200);
        Instant old = Instant.parse("2026-08-01T00:00:00Z");
        Instant recent = Instant.parse("2026-09-15T00:00:00Z");
        repository.add(new MemoryEntry("old", "périmé", "conv-1", old));
        repository.add(new MemoryEntry("new", "actif", "conv-1", recent));

        assertThat(repository.active(Instant.parse("2026-09-01T00:00:00Z")))
                .extracting(MemoryEntry::id).containsExactly("new");
    }

    @Test
    void borne_le_nombre_de_lignes_a_la_capacite() {
        JdbcMemoryRepository repository = new JdbcMemoryRepository(new JdbcTemplate(dataSource()), 2);
        Instant at = Instant.parse("2026-09-15T05:00:00Z");
        for (int i = 0; i < 5; i++) {
            repository.add(new MemoryEntry("m" + i, "fait " + i, "conv-1", at.plusSeconds(i)));
        }

        List<MemoryEntry> active = repository.active(at.minusSeconds(1));

        assertThat(active).extracting(MemoryEntry::id).containsExactly("m4", "m3");
    }
}
