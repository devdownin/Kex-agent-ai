// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.nio.file.Path;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.testsupport.FlywayTestSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessDefinitionRepositoryTest {

    private static final MonitoredProcess ORDERS = new MonitoredProcess("orders", "Commandes",
            "Flux vers ERP", "topics [orders.in, orders.out], consumer group orders-worker", null);

    @TempDir Path directory;

    @Test
    void fichier_conserve_le_processus_apres_reouverture_et_refuse_un_doublon() {
        Path path = directory.resolve("processes.json");
        FileProcessDefinitionRepository first = new FileProcessDefinitionRepository(new ObjectMapper(), path);
        first.create(ORDERS);

        FileProcessDefinitionRepository reopened = new FileProcessDefinitionRepository(new ObjectMapper(), path);
        assertThat(reopened.all()).containsExactly(ORDERS);
        assertThatThrownBy(() -> reopened.create(ORDERS)).isInstanceOf(ProcessDefinitionConflict.class);
        assertThat(new FileProcessDefinitionRepository(new ObjectMapper(), path).all()).containsExactly(ORDERS);
    }

    @Test
    void jdbc_partage_le_processus_entre_deux_repliques_et_refuse_un_doublon() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        FlywayTestSchema.migrate(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ProcessDefinitionRepository first = new JdbcProcessDefinitionRepository(jdbc);
        ProcessDefinitionRepository second = new JdbcProcessDefinitionRepository(jdbc);

        first.create(ORDERS);

        assertThat(second.all()).containsExactly(ORDERS);
        assertThatThrownBy(() -> second.create(ORDERS)).isInstanceOf(ProcessDefinitionConflict.class);
    }
}
