// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.workspace;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceStoreTest {
    @TempDir java.nio.file.Path directory;
    @Test void stockage_fichier_et_sql_isolent_les_comptes_et_refusent_les_ecritures_obsoletes() throws Exception {
        var database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        try {
            try (var connection = database.getConnection()) { ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V7__workspace_requests.sql")); }
            var json = new ObjectMapper().findAndRegisterModules();
            for (var store : List.of(new WorkspaceStore(json, directory), new WorkspaceStore(json, new JdbcTemplate(database)))) {
                assertThat(store.list("alice")).isEmpty();
                var id = UUID.randomUUID().toString(); var now = Instant.now();
                var r = new WorkspaceRequest(id, 0, "Demande", "conv", "COMPLETE", now, List.of(), List.of(), null, null);
                store.create("alice", r); assertThat(store.find("bob", id)).isEmpty(); assertThat(store.list("alice")).hasSize(1);
                var updated = new WorkspaceRequest(id, 1, "Demande", "conv", "NEEDS_INPUT", now, List.of(), List.of(), null, null);
                assertThat(store.replace("alice", updated, 0)).isTrue(); assertThat(store.replace("alice", updated, 0)).isFalse();
                assertThat(store.replace("bob", updated, 0)).isFalse(); assertThat(store.find("alice", id).orElseThrow().status()).isEqualTo("NEEDS_INPUT");
                assertThat(store.find("alice", "../secret")).isEmpty();
            }
            var second = new WorkspaceStore(json, new JdbcTemplate(database)); assertThat(second.list("alice")).hasSize(1);
        } finally { database.shutdown(); }
    }
}
