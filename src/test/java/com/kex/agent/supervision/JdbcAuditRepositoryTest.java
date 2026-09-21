// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

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
 * Sur H2 plutôt qu'un contexte Spring complet : ce qui est en jeu est le SQL et le mappage d'une
 * ligne, pas le câblage du profil {@code shared-memory} — {@code SharedMemoryProfileTest} le
 * couvre déjà pour la mémoire de conversation.
 */
class JdbcAuditRepositoryTest {

    private final JdbcAuditRepository repository = new JdbcAuditRepository(new JdbcTemplate(dataSource()));

    private static DataSource dataSource() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.h2.Driver.class);
        dataSource.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        FlywayTestSchema.migrate(dataSource);
        return dataSource;
    }

    @Test
    void persiste_et_relit_du_plus_recent_au_plus_ancien() {
        Instant first = Instant.parse("2026-09-15T05:00:00Z");
        repository.add(new AuditEntry("a1", first, "Agent", "action-1", "p1", "d1", "raison",
                "policy-v1", "resultat", "corr-1", "trace-1"));
        repository.add(new AuditEntry("a2", first.plusSeconds(5), "opérateur", "action-2", "p1",
                "d1", null, "policy-v1", "resultat-2", "corr-1", null));

        List<AuditEntry> recent = repository.recent(10);

        assertThat(recent).extracting(AuditEntry::id).containsExactly("a2", "a1");
        assertThat(recent.get(1).traceId()).isEqualTo("trace-1");
        assertThat(recent.get(0).traceId()).isNull();
    }

    @Test
    void borne_le_nombre_d_entrees_rendues() {
        Instant at = Instant.parse("2026-09-15T05:00:00Z");
        for (int i = 0; i < 5; i++) {
            repository.add(new AuditEntry("a" + i, at.plusSeconds(i), "Agent", "action", null, null,
                    null, "policy-v1", null, "corr-" + i, null));
        }

        assertThat(repository.recent(2)).hasSize(2);
    }
}
