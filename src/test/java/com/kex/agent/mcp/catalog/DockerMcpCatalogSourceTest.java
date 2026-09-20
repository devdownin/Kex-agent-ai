// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contre un vrai serveur HTTP, comme {@code LlmCatalogServiceTest} : un client moqué ne prouverait
 * rien. Le corps renvoyé reproduit la forme exacte de {@code GET
 * https://hub.docker.com/v2/repositories/mcp/}, vérifiée en direct le 20/09/2026.
 */
class DockerMcpCatalogSourceTest {

    private static final String FIRST_PAGE = """
            {"count":1,"next":null,"previous":null,"results":[
              {"name":"fetch","namespace":"mcp","description":"Fetches a URL and extracts its contents as markdown",
               "star_count":58,"pull_count":1850541,"last_updated":"2026-07-07T07:28:12.922484Z",
               "status_description":"active"}
            ]}""";

    private FakeDockerHub hub;

    @AfterEach
    void stop() {
        if (hub != null) hub.close();
    }

    @Test
    void n_appelle_pas_le_reseau_quand_desactivee() throws IOException {
        hub = new FakeDockerHub(FIRST_PAGE);
        DockerMcpCatalogSource source = source(false);

        McpCatalogSourceResult result = source.fetch();

        assertThat(result.candidates()).isEmpty();
        assertThat(result.error()).isNull();
        assertThat(hub.requestCount()).isZero();
    }

    @Test
    void traduit_une_page_docker_hub_en_candidats_notables() throws IOException {
        hub = new FakeDockerHub(FIRST_PAGE);
        DockerMcpCatalogSource source = source(true);

        McpCatalogSourceResult result = source.fetch();

        assertThat(result.error()).isNull();
        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.sourceId()).isEqualTo("docker");
            assertThat(candidate.id()).isEqualTo("fetch");
            assertThat(candidate.description()).isEqualTo("Fetches a URL and extracts its contents as markdown");
            assertThat(candidate.starCount()).isEqualTo(58L);
            assertThat(candidate.pullCount()).isEqualTo(1_850_541L);
            assertThat(candidate.updatedAt()).isEqualTo(Instant.parse("2026-07-07T07:28:12.922484Z"));
            assertThat(candidate.repositoryUrl()).isNull();
        });
    }

    @Test
    void arrete_la_pagination_quand_next_est_absent() throws IOException {
        hub = new FakeDockerHub(FIRST_PAGE);
        DockerMcpCatalogSource source = source(true);

        source.fetch();

        assertThat(hub.requestCount()).isEqualTo(1);
    }

    @Test
    void rend_une_source_en_echec_plutot_que_de_lever() throws IOException {
        hub = new FakeDockerHub(FIRST_PAGE);
        hub.respondWith(500, "{}");
        DockerMcpCatalogSource source = source(true);

        McpCatalogSourceResult result = source.fetch();

        assertThat(result.candidates()).isEmpty();
        assertThat(result.error()).isNotNull();
    }

    private DockerMcpCatalogSource source(boolean enabled) {
        McpCatalogSourcesProperties.Source dockerProperties =
                new McpCatalogSourcesProperties.Source(enabled, hub.baseUrl(), 2, 100, Duration.ofSeconds(5));
        McpCatalogSourcesProperties.Source disabled =
                new McpCatalogSourcesProperties.Source(false, "", 2, 100, Duration.ofSeconds(5));
        return new DockerMcpCatalogSource(RestClient.builder(),
                new McpCatalogSourcesProperties(dockerProperties, disabled));
    }

    /** Une passerelle Docker Hub réduite à {@code /v2/repositories/mcp/}. */
    private static final class FakeDockerHub implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();
        private volatile String body;
        private volatile int status = 200;

        FakeDockerHub(String body) throws IOException {
            this.body = body;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/v2/repositories/mcp/", exchange -> {
                requests.incrementAndGet();
                byte[] payload = this.body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, payload.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            });
            this.server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        int requestCount() {
            return requests.get();
        }

        void respondWith(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
