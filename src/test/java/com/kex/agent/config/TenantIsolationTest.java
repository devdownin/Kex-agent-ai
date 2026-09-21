// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Qui a agi et à qui appartient la donnée sont deux questions distinctes. Cloisonner sur le nom
 * de la clé les confondait : {@code ops-console} et {@code ci-pipeline}, deux intégrations de la
 * même équipe, lisaient deux chartes différentes et deux bibliothèques de compétences
 * différentes — et rien à l'écran ne le disait.
 *
 * <p>Le repli compte autant que le partage : une clé qui ne déclare aucun locataire reste seule
 * chez elle, sinon la première installation qui monte cette version verrait ses données se mêler
 * à celles d'une autre clé.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"kex.agent.api-keys.ops-console=jeton-ops", "kex.agent.api-keys.ci-pipeline=jeton-ci",
                "kex.agent.api-keys.autre-equipe=jeton-autre",
                "kex.agent.api-key-roles.ops-console=ADMIN", "kex.agent.api-key-roles.ci-pipeline=ADMIN",
                "kex.agent.api-key-roles.autre-equipe=ADMIN",
                "kex.agent.api-key-tenants.ops-console=exploitation",
                "kex.agent.api-key-tenants.ci-pipeline=exploitation",
                "kex.agent.rate-limit.enabled=false"})
@ActiveProfiles("test")
class TenantIsolationTest {

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void memoireIsolee(DynamicPropertyRegistry registry) throws IOException {
        Path directory = Files.createTempDirectory("kex-locataires");
        directory.toFile().deleteOnExit();
        registry.add("kex.agent.memory.storage-directory", directory::toString);
    }

    @Test
    void deux_cles_du_meme_locataire_lisent_la_meme_charte() throws Exception {
        put("/api/agent/charter", "Bearer jeton-ops",
                "{\"markdown\":\"# Jamais de redémarrage en journée\",\"reason\":\"règle d'équipe\"}");

        assertThat(get("/api/agent/charter", "Bearer jeton-ci"))
                .contains("Jamais de redémarrage en journée");
    }

    @Test
    void une_cle_d_un_autre_locataire_ne_voit_pas_cette_charte() throws Exception {
        put("/api/agent/charter", "Bearer jeton-ops",
                "{\"markdown\":\"# Jamais de redémarrage en journée\",\"reason\":\"règle d'équipe\"}");

        assertThat(get("/api/agent/charter", "Bearer jeton-autre"))
                .doesNotContain("Jamais de redémarrage en journée");
    }

    @Test
    void une_competence_proposee_par_une_cle_est_revisable_par_l_autre_du_meme_locataire() throws Exception {
        String proposed = post("/api/agent/skills", "Bearer jeton-ops",
                "{\"title\":\"Vérifier le lag avant de redémarrer\",\"markdown\":\"# procédure\","
                        + "\"evidence\":\"trois incidents\",\"conversationId\":null}");

        assertThat(get("/api/agent/skills", "Bearer jeton-ci"))
                .contains("Vérifier le lag avant de redémarrer");
        assertThat(get("/api/agent/skills", "Bearer jeton-autre"))
                .doesNotContain("Vérifier le lag avant de redémarrer");
        assertThat(proposed).contains("\"owner\":\"exploitation\"");
    }

    /**
     * Le locataire dit à qui appartient la donnée, jamais qui a agi : une charte écrite par
     * {@code ops-console} reste inscrite à l'audit sous ce nom-là, pas sous {@code exploitation}.
     * Confondre les deux reviendrait à remplacer une personne par une équipe dans une pièce de
     * conformité — exactement le défaut que l'axe locataire est censé ne pas introduire.
     */
    @Test
    void l_audit_nomme_toujours_la_cle_et_non_le_locataire() throws Exception {
        put("/api/agent/charter", "Bearer jeton-ops", "{\"markdown\":\"# règle\",\"reason\":\"posée\"}");

        String audit = get("/api/agent/supervision/audit", "Bearer jeton-ops");

        assertThat(audit).contains("\"actor\":\"ops-console\"").doesNotContain("\"actor\":\"exploitation\"");
    }

    private String get(String path, String authorization) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path)).header("Authorization", authorization).GET());
    }

    private String put(String path, String authorization, String json) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path)).header("Authorization", authorization)
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(json)));
    }

    private String post(String path, String authorization, String json) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path)).header("Authorization", authorization)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body();
        }
    }
}
