// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contre un vrai serveur HTTP, comme {@code LlmCatalogServiceTest}. Le corps renvoyé reproduit la
 * forme exacte de {@code GET https://registry.modelcontextprotocol.io/v0/servers}, vérifiée en
 * direct le 20/09/2026 contre l'OpenAPI de {@code modelcontextprotocol/registry}.
 */
class OfficialMcpRegistrySourceTest {

    private static final String ACTIVE_SERVER_PAGE = """
            {"servers":[
              {"name":"ac.example/x-server","title":"X Server",
               "description":"Une description suffisamment longue pour être jugée exploitable.",
               "repository":{"url":"https://github.com/x/y","source":"github"},
               "packages":[{"registryType":"npm","identifier":"x-server","version":"1.0.0","runtimeHint":"npx",
                 "environmentVariables":[{"name":"API_KEY","isRequired":true,"isSecret":true}]}],
               "remotes":[{"type":"streamable-http","url":"https://x.example.com/mcp",
                 "headers":[{"name":"Authorization"}]}],
               "_meta":{"io.modelcontextprotocol.registry/official":{"status":"active",
                 "updatedAt":"2026-08-01T00:00:00Z"}}}
            ],"metadata":{"nextCursor":null,"count":1}}""";

    private static final String DEPRECATED_SERVER_PAGE = """
            {"servers":[
              {"name":"ac.example/old","description":"Ancien serveur",
               "_meta":{"io.modelcontextprotocol.registry/official":{"status":"deprecated"}}}
            ],"metadata":{"nextCursor":null,"count":1}}""";

    private FakeRegistry registry;

    @AfterEach
    void stop() {
        if (registry != null) registry.close();
    }

    @Test
    void n_appelle_pas_le_reseau_quand_desactivee() throws IOException {
        registry = new FakeRegistry(ACTIVE_SERVER_PAGE);
        OfficialMcpRegistrySource source = source(false);

        McpCatalogSourceResult result = source.fetch();

        assertThat(result.candidates()).isEmpty();
        assertThat(registry.requestCount()).isZero();
    }

    @Test
    void traduit_un_serveur_actif_avec_paquets_et_points_distants() throws IOException {
        registry = new FakeRegistry(ACTIVE_SERVER_PAGE);
        OfficialMcpRegistrySource source = source(true);

        McpCatalogSourceResult result = source.fetch();

        assertThat(result.error()).isNull();
        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.sourceId()).isEqualTo("official-registry");
            assertThat(candidate.id()).isEqualTo("ac.example/x-server");
            assertThat(candidate.title()).isEqualTo("X Server");
            assertThat(candidate.repositoryUrl()).isEqualTo("https://github.com/x/y");
            assertThat(candidate.repositorySource()).isEqualTo("github");
            assertThat(candidate.status()).isEqualTo("active");
            assertThat(candidate.updatedAt()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
            assertThat(candidate.packages()).singleElement().satisfies(pkg -> {
                assertThat(pkg.registryType()).isEqualTo("npm");
                assertThat(pkg.identifier()).isEqualTo("x-server");
                assertThat(pkg.environmentVariables()).singleElement().satisfies(variable -> {
                    assertThat(variable.name()).isEqualTo("API_KEY");
                    assertThat(variable.required()).isTrue();
                    assertThat(variable.secret()).isTrue();
                });
            });
            assertThat(candidate.remotes()).singleElement().satisfies(remote -> {
                assertThat(remote.url()).isEqualTo("https://x.example.com/mcp");
                assertThat(remote.headerNames()).containsExactly("Authorization");
            });
        });
    }

    @Test
    void filtre_les_serveurs_depreciés() throws IOException {
        registry = new FakeRegistry(DEPRECATED_SERVER_PAGE);
        OfficialMcpRegistrySource source = source(true);

        McpCatalogSourceResult result = source.fetch();

        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void suit_le_curseur_de_pagination_et_s_arrete_quand_il_disparait() throws IOException {
        String firstPage = """
                {"servers":[{"name":"a","description":"a"}],"metadata":{"nextCursor":"page-2","count":2}}""";
        String secondPage = """
                {"servers":[{"name":"b","description":"b"}],"metadata":{"nextCursor":null,"count":2}}""";
        registry = new FakeRegistry(firstPage);
        registry.enqueue(secondPage);
        OfficialMcpRegistrySource source = source(true);

        McpCatalogSourceResult result = source.fetch();

        assertThat(result.candidates()).extracting(McpCatalogCandidate::id).containsExactly("a", "b");
        assertThat(registry.requestCount()).isEqualTo(2);
        assertThat(registry.queries().get(1)).contains("cursor=page-2");
    }

    @Test
    void rend_une_source_en_echec_plutot_que_de_lever() throws IOException {
        registry = new FakeRegistry(ACTIVE_SERVER_PAGE);
        registry.respondWith(503, "{}");
        OfficialMcpRegistrySource source = source(true);

        McpCatalogSourceResult result = source.fetch();

        assertThat(result.candidates()).isEmpty();
        assertThat(result.error()).isNotNull();
    }

    private OfficialMcpRegistrySource source(boolean enabled) {
        McpCatalogSourcesProperties.Source registryProperties =
                new McpCatalogSourcesProperties.Source(enabled, registry.baseUrl(), 5, 100, Duration.ofSeconds(5));
        McpCatalogSourcesProperties.Source disabled =
                new McpCatalogSourcesProperties.Source(false, "", 2, 100, Duration.ofSeconds(5));
        return new OfficialMcpRegistrySource(RestClient.builder(),
                new McpCatalogSourcesProperties(disabled, registryProperties));
    }

    /** Un registre officiel MCP réduit à {@code /v0/servers}. */
    private static final class FakeRegistry implements AutoCloseable {
        private final HttpServer server;
        private final List<String> queries = new CopyOnWriteArrayList<>();
        private final List<String> responses = new CopyOnWriteArrayList<>();
        private volatile int status = 200;

        FakeRegistry(String firstResponse) throws IOException {
            responses.add(firstResponse);
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/v0/servers", exchange -> {
                int index = queries.size();
                String query = exchange.getRequestURI().getQuery();
                queries.add(query == null ? "" : query);
                String body = responses.get(Math.min(index, responses.size() - 1));
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, payload.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            });
            this.server.start();
        }

        void enqueue(String response) {
            responses.add(response);
        }

        void respondWith(int status, String body) {
            this.status = status;
            responses.clear();
            responses.add(body);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        int requestCount() {
            return queries.size();
        }

        List<String> queries() {
            return queries;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
