// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrat de bout en bout du client MCP : transport streamable-HTTP réel, bearer injecté par
 * {@code McpBearerTokenCustomizer}, initialisation paresseuse puis découverte.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("mcp-it")
class McpStreamableHttpIntegrationTest {

    private static final FakeMcpServer SERVER = start();

    @Autowired
    McpToolCatalog catalog;

    private static FakeMcpServer start() {
        try {
            return new FakeMcpServer();
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @DynamicPropertySource
    static void mcpProperties(DynamicPropertyRegistry registry) {
        String url = "http://127.0.0.1:" + SERVER.port();
        registry.add("spring.ai.mcp.client.streamable-http.connections.faux.url", () -> url);
        registry.add("spring.ai.mcp.client.streamable-http.connections.faux.endpoint", () -> "/mcp");
        registry.add("kex.mcp.bearer-tokens[0].url-prefix", () -> url);
        registry.add("kex.mcp.bearer-tokens[0].token", () -> FakeMcpServer.TOKEN);
    }

    @AfterAll
    static void stop() {
        SERVER.close();
    }

    @Test
    void decouvre_le_serveur_ses_outils_et_ses_ressources() {
        // Le catalogue contient aussi la connexion kafka-explorer d'application.yml, injoignable ici.
        var servers = catalog.servers().stream().filter(s -> s.connection().equals("faux")).toList();

        assertThat(servers).singleElement().satisfies(server -> {
            assertThat(server.connection()).isEqualTo("faux");
            assertThat(server.serverName()).isEqualTo("faux-serveur");
            assertThat(server.protocolVersion()).isEqualTo("2025-06-18");
            assertThat(server.initialized()).isTrue();
            assertThat(server.tools()).extracting(McpToolInfo::name).containsExactly("echo");
        });
        // Le bearer est injecté dès le handshake : aucune requête n'a été refusée.
        assertThat(SERVER.unauthorizedCount()).isZero();
        assertThat(SERVER.methods()).contains("initialize", "tools/list");
    }

    @Test
    void appelle_un_outil_et_lit_une_ressource() {
        assertThat(catalog.call("faux", "echo", Map.of("texte", "ping")).content()).containsExactly("pong");
        assertThat(catalog.resources("faux")).extracting(McpResourceInfo::uri).containsExactly("test://ressource");
        assertThat(catalog.readResource("faux", "test://ressource"))
                .extracting(McpResourceContent::text).containsExactly("contenu");
    }
}
