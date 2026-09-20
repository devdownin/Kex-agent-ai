// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import com.kex.agent.mcp.McpServerInfo;
import com.kex.agent.mcp.McpServerRegistration;
import com.kex.agent.mcp.McpToolCatalog;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class McpCatalogDiscoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneId.of("UTC"));
    private final McpCatalogTrustProperties trustProperties = new McpCatalogTrustProperties(Duration.ofDays(180), 2,
            5, List.of("ROOT"), 40, 1000, Set.of());
    private final McpTrustScoreCalculator calculator = new McpTrustScoreCalculator(trustProperties, clock);
    private final McpToolCatalog tools = mock(McpToolCatalog.class);

    private McpCatalogDiscoveryService service(McpCatalogSource... sources) {
        return new McpCatalogDiscoveryService(List.of(sources), calculator, tools);
    }

    @Test
    void discover_merge_les_sources_et_trie_par_note_decroissante() {
        McpCatalogCandidate weak = candidate("weak", List.of(), List.of());
        McpCatalogCandidate strong = candidate("strong", List.of(pkg("npm", "x")), List.of());
        FakeSource docker = FakeSource.ok("docker", "Docker", List.of(weak, strong));
        FakeSource disabled = FakeSource.disabled("smithery", "Smithery");
        FakeSource failed = FakeSource.failed("glama", "Glama", "injoignable");

        List<McpCatalogSourceOverview> overview = service(docker, disabled, failed).discover();

        assertThat(overview).extracting(McpCatalogSourceOverview::sourceId).containsExactly("docker", "smithery", "glama");
        McpCatalogSourceOverview dockerOverview = overview.get(0);
        assertThat(dockerOverview.candidates()).extracting(c -> c.candidate().id()).containsExactly("strong", "weak");
        assertThat(overview.get(1).enabled()).isFalse();
        assertThat(overview.get(2).error()).isEqualTo("injoignable");
    }

    @Test
    void refuse_une_source_inconnue() {
        assertThatThrownBy(() -> service().install("unknown", "x", request("conn")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(tools);
    }

    @Test
    void refuse_une_source_desactivee() {
        FakeSource docker = FakeSource.disabled("docker", "Docker");
        assertThatThrownBy(() -> service(docker).install("docker", "x", request("conn")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void propage_l_echec_de_la_source_en_502() {
        FakeSource docker = FakeSource.failed("docker", "Docker", "injoignable");
        assertThatThrownBy(() -> service(docker).install("docker", "x", request("conn")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void refuse_un_candidat_inconnu() {
        FakeSource docker = FakeSource.ok("docker", "Docker", List.of(candidate("fetch", List.of(), List.of())));
        assertThatThrownBy(() -> service(docker).install("docker", "unknown", request("conn")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void refuse_un_candidat_disqualifie_sans_jamais_appeler_l_enregistrement() {
        McpCatalogPackage shell = new McpCatalogPackage("", "custom", null, "bash", List.of(), List.of(), List.of());
        McpCatalogCandidate disqualified = candidate("shell-tool", List.of(shell), List.of());
        FakeSource docker = FakeSource.ok("docker", "Docker", List.of(disqualified));

        assertThatThrownBy(() -> service(docker).install("docker", "shell-tool", request("conn")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(tools);
    }

    @Test
    void installe_un_candidat_avec_point_d_acces_distant_en_http() {
        McpCatalogRemote remote = new McpCatalogRemote("streamable-http", "https://x.example.com/streamable",
                List.of("Authorization"));
        McpCatalogCandidate candidate = candidate("remote-tool", List.of(), List.of(remote));
        FakeSource official = FakeSource.ok("official-registry", "Officiel", List.of(candidate));
        given(tools.register(any())).willReturn(info("conn"));

        service(official).install("official-registry", "remote-tool", request("conn", "bearer-secret"));

        ArgumentCaptor<McpServerRegistration> captor = ArgumentCaptor.forClass(McpServerRegistration.class);
        verify(tools).register(captor.capture());
        McpServerRegistration registration = captor.getValue();
        assertThat(registration.transport()).isEqualTo("HTTP");
        assertThat(registration.url()).isEqualTo("https://x.example.com");
        assertThat(registration.endpoint()).isEqualTo("/streamable");
        assertThat(registration.bearerToken()).isEqualTo("bearer-secret");
        assertThat(registration.enabled()).isFalse();
    }

    @Test
    void refuse_un_point_d_acces_distant_a_modele_d_url() {
        McpCatalogRemote remote = new McpCatalogRemote("streamable-http", "https://x.example.com/{tenant}/mcp",
                List.of("Authorization"));
        McpCatalogCandidate candidate = candidate("templated", List.of(), List.of(remote));
        FakeSource official = FakeSource.ok("official-registry", "Officiel", List.of(candidate));

        assertThatThrownBy(() -> service(official).install("official-registry", "templated", request("conn")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(tools);
    }

    @Test
    void installe_un_candidat_docker_en_stdio_via_l_image_mcp() {
        McpCatalogCandidate candidate = new McpCatalogCandidate("docker", "fetch", "fetch", "fetch", "desc", null,
                null, List.of(), List.of(), NOW, "active", 5_000L, null);
        FakeSource docker = FakeSource.ok("docker", "Docker", List.of(candidate));
        given(tools.register(any())).willReturn(info("conn"));

        service(docker).install("docker", "fetch", request("conn"));

        ArgumentCaptor<McpServerRegistration> captor = ArgumentCaptor.forClass(McpServerRegistration.class);
        verify(tools).register(captor.capture());
        McpServerRegistration registration = captor.getValue();
        assertThat(registration.transport()).isEqualTo("STDIO");
        assertThat(registration.command()).isEqualTo("docker");
        assertThat(registration.args()).containsExactly("run", "--rm", "-i", "mcp/fetch");
    }

    @Test
    void installe_un_candidat_avec_paquet_oci_en_stdio() {
        McpCatalogPackage oci = pkg("oci", "ghcr.io/example/tool");
        McpCatalogCandidate candidate = candidate("containerized", List.of(oci), List.of());
        FakeSource official = FakeSource.ok("official-registry", "Officiel", List.of(candidate));
        given(tools.register(any())).willReturn(info("conn"));

        service(official).install("official-registry", "containerized", request("conn"));

        ArgumentCaptor<McpServerRegistration> captor = ArgumentCaptor.forClass(McpServerRegistration.class);
        verify(tools).register(captor.capture());
        assertThat(captor.getValue().args()).containsExactly("run", "--rm", "-i", "ghcr.io/example/tool");
    }

    @Test
    void refuse_un_candidat_sans_paquet_installable() {
        McpCatalogCandidate candidate = candidate("npm-only", List.of(pkg("npm", "x")), List.of());
        FakeSource official = FakeSource.ok("official-registry", "Officiel", List.of(candidate));

        assertThatThrownBy(() -> service(official).install("official-registry", "npm-only", request("conn")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(tools);
    }

    private static McpCatalogInstallRequest request(String connection) {
        return request(connection, null);
    }

    private static McpCatalogInstallRequest request(String connection, String bearerToken) {
        return new McpCatalogInstallRequest(connection, bearerToken);
    }

    private static McpServerInfo info(String connection) {
        return new McpServerInfo(connection, "kex-agent - " + connection, "1.0.0", "2025-06-18", false, "CLOSED",
                List.of());
    }

    private static McpCatalogCandidate candidate(String id, List<McpCatalogPackage> packages,
                                                 List<McpCatalogRemote> remotes) {
        return new McpCatalogCandidate("official-registry", id, id, id, "Une description exploitable et détaillée.",
                packages.isEmpty() && remotes.isEmpty() ? null : "https://github.com/x/" + id, "github", packages,
                remotes, NOW, "active", 5_000L, null);
    }

    private static McpCatalogPackage pkg(String registryType, String identifier) {
        return new McpCatalogPackage(registryType, identifier, null, null, List.of(), List.of(), List.of());
    }

    private static final class FakeSource implements McpCatalogSource {
        private final String id;
        private final String label;
        private final boolean enabled;
        private final McpCatalogSourceResult result;

        private FakeSource(String id, String label, boolean enabled, McpCatalogSourceResult result) {
            this.id = id;
            this.label = label;
            this.enabled = enabled;
            this.result = result;
        }

        static FakeSource ok(String id, String label, List<McpCatalogCandidate> candidates) {
            return new FakeSource(id, label, true, McpCatalogSourceResult.ok(id, label, candidates));
        }

        static FakeSource disabled(String id, String label) {
            return new FakeSource(id, label, false, null);
        }

        static FakeSource failed(String id, String label, String error) {
            return new FakeSource(id, label, true, McpCatalogSourceResult.failed(id, label, error));
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public McpCatalogSourceResult fetch() {
            return result;
        }
    }
}
