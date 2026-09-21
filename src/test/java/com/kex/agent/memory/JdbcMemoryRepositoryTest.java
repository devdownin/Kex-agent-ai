// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

import com.kex.agent.testsupport.FlywayTestSchema;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sur H2 plutôt qu'un contexte Spring complet : ce qui est en jeu est le SQL, le mappage d'une
 * ligne, la borne de capacité et la supersession, pas le câblage du profil {@code shared-memory}.
 */
class JdbcMemoryRepositoryTest {

    private static DataSource dataSource() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.h2.Driver.class);
        dataSource.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        FlywayTestSchema.migrate(dataSource);
        return dataSource;
    }

    private static JdbcMemoryRepository repository(int capacity) {
        return new JdbcMemoryRepository(new JdbcTemplate(dataSource()), capacity);
    }

    @Test
    void persiste_et_relit_du_plus_recent_au_plus_ancien() {
        JdbcMemoryRepository repository = repository(200);
        Instant first = Instant.parse("2026-09-15T05:00:00Z");
        repository.add(new MemoryEntry("m1", "premier fait", "conv-1", first, null));
        repository.add(new MemoryEntry("m2", "second fait", "conv-1", first.plusSeconds(5), null));

        List<MemoryEntry> active = repository.active(first.minusSeconds(1));

        assertThat(active).extracting(MemoryEntry::id).containsExactly("m2", "m1");
    }

    @Test
    void filtre_les_entrees_perimees() {
        JdbcMemoryRepository repository = repository(200);
        Instant old = Instant.parse("2026-08-01T00:00:00Z");
        Instant recent = Instant.parse("2026-09-15T00:00:00Z");
        repository.add(new MemoryEntry("old", "périmé", "conv-1", old, null));
        repository.add(new MemoryEntry("new", "actif", "conv-1", recent, null));

        assertThat(repository.active(Instant.parse("2026-09-01T00:00:00Z")))
                .extracting(MemoryEntry::id).containsExactly("new");
    }

    @Test
    void borne_le_nombre_de_lignes_a_la_capacite() {
        JdbcMemoryRepository repository = repository(2);
        Instant at = Instant.parse("2026-09-15T05:00:00Z");
        for (int i = 0; i < 5; i++) {
            repository.add(new MemoryEntry("m" + i, "fait " + i, "conv-1", at.plusSeconds(i), null));
        }

        List<MemoryEntry> active = repository.active(at.minusSeconds(1));

        assertThat(active).extracting(MemoryEntry::id).containsExactly("m4", "m3");
    }

    @Test
    void un_souvenir_remplace_n_est_plus_actif() {
        JdbcMemoryRepository repository = repository(200);
        Instant at = Instant.parse("2026-09-15T05:00:00Z");
        repository.add(new MemoryEntry("ancien", "version 1", "conv-1", at, null));
        repository.add(new MemoryEntry("nouveau", "version 2", "conv-1", at.plusSeconds(1), null));

        assertThat(repository.supersede("ancien", "nouveau")).isTrue();
        assertThat(repository.active(at.minusSeconds(1)))
                .extracting(MemoryEntry::id).containsExactly("nouveau");
    }

    @Test
    void ne_remarque_ni_un_inconnu_ni_un_deja_remplace() {
        JdbcMemoryRepository repository = repository(200);
        Instant at = Instant.parse("2026-09-15T05:00:00Z");
        repository.add(new MemoryEntry("ancien", "version 1", "conv-1", at, null));
        repository.supersede("ancien", "nouveau");

        assertThat(repository.supersede("ancien", "encore-un-autre")).isFalse();
        assertThat(repository.supersede("jamais-écrit", "nouveau")).isFalse();
    }
}
