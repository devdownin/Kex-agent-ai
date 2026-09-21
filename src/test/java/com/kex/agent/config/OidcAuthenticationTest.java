// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le point de la version professionnelle : « approuvé par ops-console » nomme une clé partagée,
 * pas une personne, et un registre de conformité qui ne sait pas dire qui a agi ne vaut pas
 * grand-chose. Un jeton OIDC y met un nom.
 *
 * <p>Le décodeur est fourni par le test plutôt qu'un émetteur réel : ce qui est vérifié ici est le
 * câblage — la chaîne JWT, la traduction des rôles, l'acteur inscrit à l'audit, et surtout la
 * cohabitation avec les clés API. La validation cryptographique du jeton est celle de Spring
 * Security, elle n'a pas à être retestée.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"kex.agent.api-keys.ops-console=jeton-ops",
                "kex.agent.api-key-roles.ops-console=OPERATOR",
                "kex.agent.oidc.role-mappings.kex-ops=OPERATOR",
                "kex.agent.oidc.role-mappings.kex-admins=ADMIN",
                "kex.agent.oidc.tenant-claim=org",
                "kex.agent.rate-limit.enabled=false"})
@ActiveProfiles("test")
class OidcAuthenticationTest {

    @LocalServerPort
    int port;

    @TestConfiguration
    static class DecodeurFactice {

        /**
         * Trois jetons : une opératrice, une administratrice, et tout le reste qui est refusé.
         * La présence de ce bean suffit à activer la chaîne JWT — c'est exactement la condition
         * que {@code SecurityConfig} examine.
         */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> switch (token) {
                case "jwt-alice" -> jwt(token, "alice@exemple.fr", List.of("kex-ops"));
                case "jwt-bernard" -> jwt(token, "bernard@exemple.fr", List.of("kex-admins"));
                default -> throw new BadJwtException("Jeton refusé");
            };
        }

        private static Jwt jwt(String token, String username, List<String> roles) {
            return Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .subject(username)
                    .issuedAt(Instant.parse("2026-09-21T10:00:00Z"))
                    .expiresAt(Instant.parse("2026-09-21T18:00:00Z"))
                    .claims(claims -> claims.putAll(Map.of("preferred_username", username,
                            "roles", roles, "org", "exploitation")))
                    .build();
        }
    }

    @Test
    void l_audit_inscrit_la_personne_et_non_une_cle_partagee() throws Exception {
        assertThat(status("/api/agent/supervision/pause", "Bearer jwt-alice", "")).isEqualTo(200);

        String audit = body("/api/agent/supervision/audit", "Bearer jwt-alice");

        assertThat(audit).contains("\"actor\":\"alice@exemple.fr\"");
    }

    /**
     * La régression que le résolveur de jeton existe pour empêcher :
     * {@code BearerTokenAuthenticationFilter} n'examine pas si quelqu'un s'est authentifié avant
     * lui, et lirait une clé API comme un JWT malformé. Déclarer un émetteur aurait alors mis
     * toute une installation existante en 401.
     */
    @Test
    void une_cle_api_continue_de_fonctionner_a_cote_d_un_emetteur_oidc() throws Exception {
        assertThat(status("/api/agent/mcp/servers", "Bearer jeton-ops")).isEqualTo(200);
    }

    @Test
    void un_jeton_inconnu_est_refuse() throws Exception {
        assertThat(status("/api/agent/mcp/servers", "Bearer jwt-inconnu")).isEqualTo(401);
    }

    @Test
    void les_roles_du_jeton_decident_de_ce_qui_est_permis() throws Exception {
        // Approuver une compétence exige ADMIN : l'opératrice est refusée, l'administratrice
        // passe la sécurité et bute sur la compétence inconnue.
        assertThat(status("/api/agent/skills/inconnue/approve", "Bearer jwt-alice", "{}")).isEqualTo(403);
        assertThat(status("/api/agent/skills/inconnue/approve", "Bearer jwt-bernard", "{}")).isEqualTo(409);
    }

    private int status(String path, String authorization) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path)).header("Authorization", authorization)).statusCode();
    }

    private int status(String path, String authorization, String json) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path)).header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))).statusCode();
    }

    private String body(String path, String authorization) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path)).header("Authorization", authorization)).body();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static HttpResponse<String> send(HttpRequest.Builder builder)
            throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
