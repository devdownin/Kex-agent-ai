// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptedMcpServerStoreTest {

    @TempDir
    Path directory;

    @Test
    void chiffre_et_authentifie_les_secrets_persistes() throws Exception {
        Path file = directory.resolve("servers.enc");
        McpRuntimeProperties properties = new McpRuntimeProperties(file.toString(), "cle-maitresse-de-test",
                Duration.ofSeconds(30), 10, List.of());
        EncryptedMcpServerStore store = new EncryptedMcpServerStore(mapper(), properties);
        McpServerRegistration registration = new McpServerRegistration("runbook", "HTTP",
                "https://mcp.example.net", "/mcp", "secret-bearer", Map.of("X-Api-Key", "secret-header"),
                null, List.of(), Map.of(), true, Set.of("restart"), Map.of("restart", "RUNBOOK"));

        store.save(List.of(new EncryptedMcpServerStore.PersistedServer(registration, Instant.EPOCH)));

        assertThat(Files.readString(file)).doesNotContain("secret-bearer", "secret-header", "mcp.example.net");
        assertThat(store.load()).singleElement().satisfies(entry -> {
            assertThat(entry.registration()).isEqualTo(registration);
            assertThat(entry.secretRotatedAt()).isEqualTo(Instant.EPOCH);
        });

        EncryptedMcpServerStore wrongKey = new EncryptedMcpServerStore(mapper(),
                new McpRuntimeProperties(file.toString(), "autre-cle", Duration.ofSeconds(30), 10, List.of()));
        assertThatThrownBy(wrongKey::load).isInstanceOf(McpStorageException.class)
                .hasMessageContaining("déchiffrer");
    }

    @Test
    void deux_ecritures_successives_utilisent_un_sel_different() throws Exception {
        Path file = directory.resolve("servers.enc");
        McpRuntimeProperties properties = new McpRuntimeProperties(file.toString(), "cle-maitresse-de-test",
                Duration.ofSeconds(30), 10, List.of());
        EncryptedMcpServerStore store = new EncryptedMcpServerStore(mapper(), properties);

        store.save(List.of());
        String firstSalt = Files.readString(file).split("\\.", 4)[1];
        store.save(List.of());
        String secondSalt = Files.readString(file).split("\\.", 4)[1];

        // Sans sel propre à chaque écriture, une passphrase faible resterait cassable hors ligne
        // par une seule table précalculée, quel que soit le nombre d'itérations PBKDF2.
        assertThat(firstSalt).isNotEqualTo(secondSalt);
    }

    @Test
    void refuse_un_fichier_de_l_ancien_format_sans_sel() throws Exception {
        Path file = directory.resolve("servers.enc");
        // KEXMCP1 : hachage SHA-256 nu, sans sel, remplacé par KEXMCP2 (PBKDF2 + sel). Un fichier
        // de l'ancien format doit échouer explicitement, jamais se relire en silence.
        Files.writeString(file, "KEXMCP1.bm9uY2U.Y2lwaGVydGV4dA");
        EncryptedMcpServerStore store = new EncryptedMcpServerStore(mapper(),
                new McpRuntimeProperties(file.toString(), "cle-maitresse-de-test", Duration.ofSeconds(30), 10,
                        List.of()));

        assertThatThrownBy(store::load).isInstanceOf(IllegalStateException.class).hasMessageContaining("inconnu");
    }

    @Test
    void n_ecrit_rien_sans_cle_de_stockage() {
        Path file = directory.resolve("disabled.enc");
        EncryptedMcpServerStore store = new EncryptedMcpServerStore(mapper(),
                new McpRuntimeProperties(file.toString(), "", Duration.ofSeconds(30), 10, List.of()));

        store.save(List.of());

        assertThat(store.enabled()).isFalse();
        assertThat(file).doesNotExist();
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
