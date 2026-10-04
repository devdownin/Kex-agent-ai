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
    void inspecte_les_services_sans_activer_une_connexion_desactivee() {
        String connection = "preview-faux";
        catalog.register(new McpServerRegistration(connection, "HTTP",
                "http://127.0.0.1:" + SERVER.port(), "/mcp", FakeMcpServer.TOKEN,
                Map.of(), null, List.of(), Map.of(), false, Set.of("echo"), Map.of()));
        try {
            McpServerInfo preview = catalog.inspect(connection);
            assertThat(preview.serverName()).isEqualTo("faux-serveur");
            assertThat(preview.protocolVersion()).isEqualTo("2025-06-18");
            assertThat(preview.tools()).extracting(McpToolInfo::name).containsExactly("echo");
            assertThat(catalog.runtimeServers().stream()
                    .filter(server -> server.connection().equals(connection)))
                    .singleElement().satisfies(server -> assertThat(server.enabled()).isFalse());
            assertThat(catalog.dynamicServers()).extracting(McpServerInfo::connection).doesNotContain(connection);
            assertThat(catalog.diagnostics(connection).connected()).isFalse();
        }
        finally {
            catalog.unregister(connection);
        }
    }

    @Test
    void presente_les_effets_declares_et_distingue_un_catalogue_filtre() throws IOException {
        try (FakeMcpServer annotated = new FakeMcpServer().withToolsList("""
                {"tools":[{"name":"search","description":"Rechercher les topics",
                  "inputSchema":{"type":"object","required":["topic"],
                    "properties":{"topic":{"type":"string","description":"Topic à examiner"}}},
                  "annotations":{"readOnlyHint":true,"destructiveHint":false}}]}
                """)) {
            String connection = "preview-annotations";
            catalog.register(new McpServerRegistration(connection, "HTTP",
                    "http://127.0.0.1:" + annotated.port(), "/mcp", FakeMcpServer.TOKEN,
                    Map.of(), null, List.of(), Map.of(), false, Set.of(), Map.of()));
            try {
                McpServerInfo preview = catalog.inspect(connection);
                assertThat(preview.reportedToolCount()).isEqualTo(1);
                assertThat(preview.tools()).singleElement().satisfies(tool -> {
                    assertThat(tool.annotations()).containsEntry("readOnlyHint", true)
                            .containsEntry("destructiveHint", false);
                    assertThat(tool.readOnlyByPolicy()).isFalse();
                    assertThat(tool.inputSchema()).containsEntry("required", List.of("topic"));
                });
                catalog.update(connection, new McpServerRegistration(connection, "HTTP",
                        "http://127.0.0.1:" + annotated.port(), "/mcp", null,
                        Map.of(), null, List.of(), Map.of(), false, Set.of("not-authorized"), Map.of()));
                McpServerInfo filtered = catalog.inspect(connection);
                assertThat(filtered.tools()).isEmpty();
                assertThat(filtered.reportedToolCount()).isEqualTo(1);
            }
            finally {
                catalog.unregister(connection);
            }
        }
    }

    @Test
    void conserve_le_catalogue_date_et_ses_ressources_et_prompts_apres_un_echec() throws IOException {
        try (FakeMcpServer source = new FakeMcpServer()
                .withCapabilities("{\"tools\":{},\"resources\":{},\"prompts\":{}}")
                .withCatalogResult("resources/templates/list", """
                        {"resourceTemplates":[{"uriTemplate":"test://topics/{topic}","name":"Topic",
                         "description":"Métadonnées d'un topic","mimeType":"application/json"}]}
                        """)
                .withCatalogResult("prompts/list", """
                        {"prompts":[{"name":"triage","description":"Analyser une anomalie",
                         "arguments":[{"name":"topic","description":"Topic concerné","required":true}]}],"nextCursor":"second"}
                        """)
                .withCatalogResult("prompts/list:second", "{\"prompts\":[{\"name\":\"bilan\",\"arguments\":[]}]}")) {
            String connection = "preview-cache";
            catalog.register(new McpServerRegistration(connection, "HTTP",
                    "http://127.0.0.1:" + source.port(), "/mcp", FakeMcpServer.TOKEN,
                    Map.of(), null, List.of(), Map.of(), false, Set.of(), Map.of()));
            try {
                McpServerInfo first = catalog.inspect(connection);
                assertThat(first.retrievedAt()).isNotNull();
                assertThat(first.cached()).isFalse();
                assertThat(first.resources()).extracting(McpResourceInfo::uri).containsExactly("test://ressource");
                assertThat(first.resourceTemplates()).extracting(McpResourceTemplateInfo::uriTemplate)
                        .containsExactly("test://topics/{topic}");
                assertThat(first.prompts()).extracting(McpPromptInfo::name).containsExactly("triage", "bilan");
                assertThat(first.prompts()).first().satisfies(prompt -> {
                    assertThat(prompt.name()).isEqualTo("triage");
                    assertThat(prompt.arguments()).containsExactly(new McpPromptInfo.Argument("topic", "Topic concerné", true));
                });
                int requests = source.methods().size();
                assertThat(catalog.inspect(connection).cached()).isTrue();
                assertThat(source.methods()).hasSize(requests);
                source.failMethod("resources/list");
                McpServerInfo stale = catalog.inspect(connection, true);
                assertThat(stale.stale()).isTrue();
                assertThat(stale.retrievedAt()).isEqualTo(first.retrievedAt());
                assertThat(stale.resources()).isEqualTo(first.resources());
                assertThat(stale.prompts()).isEqualTo(first.prompts());
                assertThat(stale.refreshError()).isNotBlank();
                assertThat(catalog.inspect(connection).stale()).isTrue();
                source.failMethod(null);
                source.withToolsList("{\"tools\":[]}");
                McpServerInfo updated = catalog.inspect(connection, true);
                assertThat(updated.stale()).isFalse();
                assertThat(updated.tools()).isEmpty();
                assertThat(updated.retrievedAt()).isAfterOrEqualTo(first.retrievedAt());
                assertThat(source.methods()).doesNotContain("resources/read", "prompts/get");
            }
            finally {
                catalog.unregister(connection);
            }
        }
    }

    @Test
    void decouvre_un_serveur_sans_outils_et_refuse_une_pagination_cyclique() throws IOException {
        try (FakeMcpServer source = new FakeMcpServer().withCapabilities("{\"prompts\":{}}")) {
            String connection = "preview-prompts";
            catalog.register(new McpServerRegistration(connection, "HTTP",
                    "http://127.0.0.1:" + source.port(), "/mcp", FakeMcpServer.TOKEN,
                    Map.of(), null, List.of(), Map.of(), false, Set.of(), Map.of()));
            try {
                int before = source.methods().size();
                McpServerInfo first = catalog.inspect(connection);
                assertThat(first.supportedCapabilities()).containsExactly("prompts");
                assertThat(source.methods().subList(before, source.methods().size()))
                        .doesNotContain("tools/list", "resources/list");
                source.withCatalogResult("prompts/list", "{\"prompts\":[],\"nextCursor\":\"same\"}");
                assertThat(catalog.inspect(connection, true).stale()).isTrue();
                // L'invalidation empêche aussi de réutiliser un catalogue après changement d'identité/adresse.
                catalog.unregister(connection);
                catalog.register(new McpServerRegistration(connection, "HTTP",
                        "http://127.0.0.1:" + source.port(), "/mcp", FakeMcpServer.TOKEN,
                        Map.of(), null, List.of(), Map.of(), false, Set.of(), Map.of()));
                assertThatThrownBy(() -> catalog.inspect(connection)).isInstanceOf(McpServerUnavailableException.class);
            }
            finally {
                catalog.unregister(connection);
            }
        }
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
