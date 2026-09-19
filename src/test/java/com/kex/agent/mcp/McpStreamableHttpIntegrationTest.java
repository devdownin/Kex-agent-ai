// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void administre_une_connexion_runtime_et_applique_les_permissions() {
        McpServerRegistration registration = new McpServerRegistration(
                "runtime-faux", "HTTP", "http://127.0.0.1:" + SERVER.port(), "/mcp",
                FakeMcpServer.TOKEN, Map.of("X-Kex-Test", "runtime"), null, List.of(), Map.of(), true,
                Set.of("echo"), Map.of("echo", "RUNBOOK"));
        McpServerRegistration second = new McpServerRegistration(
                "runtime-second", "HTTP", "http://127.0.0.1:" + SERVER.port(), "/mcp",
                FakeMcpServer.TOKEN, Map.of(), null, List.of(), Map.of(), true, Set.of(), Map.of());

        try {
            McpConnectionTestResult test = catalog.test(registration);
            McpServerInfo registered = catalog.register(registration);
            McpConfigurationBundle exported = catalog.exportConfiguration();
            catalog.register(second);

            assertThat(test.success()).isTrue();
            assertThat(registered.connection()).isEqualTo("runtime-faux");
            assertThat(catalog.runtimeServers()).hasSize(2).first().satisfies(server -> {
                assertThat(server.transport()).isEqualTo("HTTP");
                assertThat(server.hasBearerToken()).isTrue();
            });
            assertThat(catalog.diagnostics("runtime-faux").conflicts())
                    .anyMatch(conflict -> conflict.contains("echo") && conflict.contains("runtime-second"));
            McpServerDiagnostics refreshed = catalog.refresh("runtime-faux");
            assertThat(refreshed.healthHistory()).isNotEmpty();
            assertThat(refreshed.toolDiff().hasChanges()).isFalse();
            assertThat(exported.servers()).singleElement().satisfies(server -> {
                assertThat(server.enabled()).isFalse();
                assertThat(server.bearerToken()).isNull();
            });

            McpServerRegistration restricted = new McpServerRegistration(
                    "runtime-faux", "HTTP", "http://127.0.0.1:" + SERVER.port(), "/mcp", null,
                    Map.of("X-Kex-Test", ""), null, List.of(), Map.of(), true,
                    Set.of("outil-interdit"), Map.of("outil-interdit", "RUNBOOK"));
            assertThat(catalog.update("runtime-faux", restricted).headerNames()).contains("X-Kex-Test");
            assertThatThrownBy(() -> catalog.call("runtime-faux", "echo", Map.of()))
                    .isInstanceOf(McpToolForbiddenException.class);

            assertThat(catalog.setEnabled("runtime-faux", false).enabled()).isFalse();
            assertThat(catalog.rotateSecret("runtime-faux",
                    new McpSecretRotation(FakeMcpServer.TOKEN, Map.of(), Map.of())).secretRotatedAt()).isNotNull();
            assertThat(catalog.setEnabled("runtime-faux", true).enabled()).isTrue();

            catalog.unregister("runtime-second");
            catalog.unregister("runtime-faux");
            assertThat(catalog.importConfiguration(exported)).singleElement()
                    .extracting(McpRuntimeServerView::enabled).isEqualTo(false);
            assertThat(catalog.storageStatus()).containsEntry("encryptedPersistence", false);
        }
        finally {
            catalog.dynamicConnectionNames().stream().filter(name -> name.startsWith("runtime-"))
                    .toList().forEach(catalog::unregister);
        }
    }
}
