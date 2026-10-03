// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.testsupport.FlywayTestSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ForecastAssociationRepositoryTest {
    @TempDir Path directory;
    private final List<ForecastAssociation> links = List.of(new ForecastAssociation("series-a", "lab"));

    @Test
    void file_survives_restart_and_empty_override_does_not_restore_configured_links() throws Exception {
        var path = directory.resolve("associations.json");
        var first = new FileForecastAssociationRepository(new ObjectMapper(), path);
        first.replace("orders", links);
        first.replace("other", links);
        var second = new FileForecastAssociationRepository(new ObjectMapper(), path);
        assertThat(second.all().get("orders")).isEqualTo(links);
        second.replace("orders", List.of());
        var reopened = new FileForecastAssociationRepository(new ObjectMapper(), path);
        assertThat(reopened.all()).containsEntry("orders", List.of()).containsEntry("other", links);
        var broken = directory.resolve("broken");
        Files.writeString(broken, "invalid");
        assertThatThrownBy(() -> new FileForecastAssociationRepository(new ObjectMapper(), broken)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failed_file_write_keeps_previous_state() throws Exception {
        var parent = directory.resolve("blocked");
        var repository = new FileForecastAssociationRepository(new ObjectMapper(), parent.resolve("links.json"));
        Files.writeString(parent, "not a directory");
        assertThatThrownBy(() -> repository.replace("orders", links)).isInstanceOf(IllegalStateException.class);
        assertThat(repository.all()).isEmpty();
    }

    @Test
    void jdbc_replicas_share_atomic_replacements_and_empty_overrides() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        FlywayTestSchema.migrate(ds);
        var first = new JdbcForecastAssociationRepository(new JdbcTemplate(ds), new ObjectMapper());
        var second = new JdbcForecastAssociationRepository(new JdbcTemplate(ds), new ObjectMapper());
        first.replace("orders", links);
        assertThat(second.all()).containsEntry("orders", links);
        second.replace("orders", List.of());
        assertThat(first.all()).containsEntry("orders", List.of());
    }
}
