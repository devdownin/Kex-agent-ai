// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpToolCatalogTest {

    @Mock
    McpSyncClient client;

    @Mock
    McpSyncClient other;

    private McpToolCatalog catalog(boolean initialized) {
        given(client.getClientInfo()).willReturn(new McpSchema.Implementation("kex-agent - kafka-explorer", "0.1.0"));
        given(client.isInitialized()).willReturn(initialized);
        // Une seule tentative : la résilience a ses propres tests plus bas, ceux-ci portent sur le
        // comportement fonctionnel et ne doivent pas se mettre à réessayer ou à attendre pour rien.
        RetryRegistry retryRegistry = RetryRegistry.ofDefaults();
        retryRegistry.retry("mcp-tool", RetryConfig.custom().maxAttempts(1).build());
        return new McpToolCatalog(List.of(client), ObservationRegistry.NOOP,
                CircuitBreakerRegistry.ofDefaults(), retryRegistry, new SimpleMeterRegistry());
    }

    private void withResources() {
        given(client.getServerCapabilities())
                .willReturn(McpSchema.ServerCapabilities.builder().resources(false, false).build());
    }

    @Test
    void expose_les_outils_sous_la_cle_de_connexion() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("kex_list_topics")
                .description("Liste les topics")
                .inputSchema(Map.of("type", "object"))
                .build();
        given(client.getServerInfo()).willReturn(new McpSchema.Implementation("kafka-explorer-mcp", "0.1.0"));
        given(client.listTools()).willReturn(new McpSchema.ListToolsResult(List.of(tool), null));

        List<McpServerInfo> servers = catalog(true).servers();

        assertThat(servers).singleElement().satisfies(server -> {
            assertThat(server.connection()).isEqualTo("kafka-explorer");
            assertThat(server.serverName()).isEqualTo("kafka-explorer-mcp");
            assertThat(server.initialized()).isTrue();
            assertThat(server.tools()).containsExactly(
                    new McpToolInfo("kex_list_topics", "Liste les topics", Map.of("type", "object")));
            assertThat(server.circuitBreakerState()).isEqualTo("CLOSED");
        });
    }

    @Test
    void compare_les_outils_ajoutes_supprimes_et_les_schemas_modifies() {
        Instant comparedAt = Instant.parse("2026-09-19T10:15:30Z");
        Map<String, McpToolInfo> previous = Map.of(
                "removed", new McpToolInfo("removed", "Ancien", Map.of("type", "object")),
                "changed", new McpToolInfo("changed", "Stable", Map.of("required", List.of("id"))),
                "stable", new McpToolInfo("stable", "Stable", Map.of("type", "string")));
        Map<String, McpToolInfo> current = Map.of(
                "added", new McpToolInfo("added", "Nouveau", Map.of("type", "object")),
                "changed", new McpToolInfo("changed", "Stable", Map.of("required", List.of("id", "force"))),
                "stable", new McpToolInfo("stable", "Description modifiée", Map.of("type", "string")));

        McpToolDiff diff = McpToolCatalog.compareTools(previous, current, comparedAt);

        assertThat(diff.comparedAt()).isEqualTo(comparedAt);
        assertThat(diff.added()).containsExactly("added");
        assertThat(diff.removed()).containsExactly("removed");
        assertThat(diff.schemaChanged()).containsExactly("changed");
        assertThat(diff.hasChanges()).isTrue();
    }

    @Test
    void liste_un_serveur_injoignable_sans_echouer() {
        given(client.initialize()).willThrow(new IllegalStateException("connection refused"));

        List<McpServerInfo> servers = catalog(false).servers();

        assertThat(servers).singleElement().satisfies(server -> {
            assertThat(server.connection()).isEqualTo("kafka-explorer");
            assertThat(server.initialized()).isFalse();
            assertThat(server.tools()).isEmpty();
        });
    }

    @Test
    void initialise_a_la_demande_un_client_encore_muet() {
        given(client.callTool(any(McpSchema.CallToolRequest.class))).willReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("ok")), false, null, null));

        catalog(false).call("kafka-explorer", "kex_list_topics", Map.of());

        verify(client).initialize();
    }

    @Test
    void signale_un_serveur_injoignable_a_l_appel() {
        given(client.initialize()).willThrow(new IllegalStateException("connection refused"));
        McpToolCatalog catalog = catalog(false);

        assertThatThrownBy(() -> catalog.call("kafka-explorer", "kex_list_topics", Map.of()))
                .isInstanceOf(McpServerUnavailableException.class)
                .hasMessageContaining("kafka-explorer");
    }

    @Test
    void appelle_l_outil_du_serveur_cible() {
        given(client.callTool(new McpSchema.CallToolRequest("kex_list_topics", Map.of("prefix", "demo."))))
                .willReturn(new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("demo.orders")), false, null, null));

        McpToolResult result = catalog(true).call("kafka-explorer", "kex_list_topics", Map.of("prefix", "demo."));

        assertThat(result.error()).isFalse();
        assertThat(result.content()).containsExactly("demo.orders");
        verify(client, never()).initialize();
    }

    @Test
    void remonte_l_echec_signale_par_l_outil() {
        given(client.callTool(any(McpSchema.CallToolRequest.class))).willReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("rate limited")), true, null, null));

        McpToolResult result = catalog(true).call("kafka-explorer", "kex_sql_query", null);

        assertThat(result.error()).isTrue();
        assertThat(result.content()).containsExactly("rate limited");
    }

    @Test
    void rejette_une_connexion_inconnue() {
        McpToolCatalog catalog = catalog(true);

        assertThatThrownBy(() -> catalog.call("absent", "kex_list_topics", Map.of()))
                .isInstanceOf(UnknownMcpServerException.class)
                .hasMessageContaining("absent");
    }

    @Test
    void liste_les_ressources_du_serveur() {
        withResources();
        given(client.listResources()).willReturn(new McpSchema.ListResourcesResult(
                List.of(new McpSchema.Resource("kafka://cluster/topics", "topics", null, "Topics du cluster",
                        "application/json", null, null, null)),
                null));

        List<McpResourceInfo> resources = catalog(true).resources("kafka-explorer");

        assertThat(resources).containsExactly(
                new McpResourceInfo("kafka://cluster/topics", "topics", "Topics du cluster", "application/json", null));
    }

    @Test
    void lit_une_ressource_texte() {
        withResources();
        given(client.readResource(new McpSchema.ReadResourceRequest("kafka://cluster/topics")))
                .willReturn(new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents("kafka://cluster/topics", "application/json", "[]"))));

        List<McpResourceContent> contents = catalog(true).readResource("kafka-explorer", "kafka://cluster/topics");

        assertThat(contents).containsExactly(
                new McpResourceContent("kafka://cluster/topics", "application/json", "[]", null));
    }

    @Test
    void ne_liste_rien_si_le_serveur_n_expose_pas_de_ressources() {
        given(client.getServerCapabilities()).willReturn(McpSchema.ServerCapabilities.builder().tools(false).build());

        assertThat(catalog(true).resources("kafka-explorer")).isEmpty();
    }

    @Test
    void refuse_la_lecture_si_le_serveur_n_expose_pas_de_ressources() {
        given(client.getServerCapabilities()).willReturn(McpSchema.ServerCapabilities.builder().tools(false).build());
        McpToolCatalog catalog = catalog(true);

        assertThatThrownBy(() -> catalog.readResource("kafka-explorer", "kafka://cluster/topics"))
                .isInstanceOf(UnsupportedMcpCapabilityException.class);
    }

    @Test
    void degrade_proprement_si_le_listing_des_outils_echoue() {
        given(client.getServerInfo()).willReturn(new McpSchema.Implementation("kafka-explorer-mcp", "0.1.0"));
        given(client.listTools()).willThrow(new IllegalStateException("transport closed"));

        assertThat(catalog(true).servers()).singleElement()
                .extracting(McpServerInfo::tools)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .isEmpty();
    }

    @Test
    void retente_un_serveur_injoignable_avant_d_abandonner() {
        given(client.getClientInfo()).willReturn(new McpSchema.Implementation("kex-agent - kafka-explorer", "0.1.0"));
        given(client.isInitialized()).willReturn(false);
        // Injoignable une fois, puis de retour : c'est exactement le blip transitoire que le
        // réessai existe pour absorber, contrairement à une erreur de protocole qui resterait
        // fausse rejouée.
        given(client.initialize())
                .willThrow(new IllegalStateException("connection refused"))
                .willReturn((McpSchema.InitializeResult) null);
        given(client.callTool(any(McpSchema.CallToolRequest.class))).willReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("ok")), false, null, null));

        RetryRegistry retryRegistry = RetryRegistry.ofDefaults();
        retryRegistry.retry("mcp-tool", RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(1))
                .retryExceptions(McpServerUnavailableException.class)
                .build());
        McpToolCatalog catalog = new McpToolCatalog(List.of(client), ObservationRegistry.NOOP,
                CircuitBreakerRegistry.ofDefaults(), retryRegistry, new SimpleMeterRegistry());

        McpToolResult result = catalog.call("kafka-explorer", "kex_list_topics", Map.of());

        assertThat(result.error()).isFalse();
        verify(client, times(2)).initialize();
    }

    @Test
    void echoue_vite_quand_le_disjoncteur_est_ouvert() {
        given(client.getClientInfo()).willReturn(new McpSchema.Implementation("kex-agent - kafka-explorer", "0.1.0"));
        given(client.isInitialized()).willReturn(false);
        given(client.initialize()).willThrow(new IllegalStateException("connection refused"));

        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
        // Pré-enregistré sous le nom que le catalogue donnera lui-même à sa construction
        // ("mcp-tool-" + connexion) : la config par défaut n'ouvrirait qu'après davantage d'appels.
        circuitBreakerRegistry.circuitBreaker("mcp-tool-kafka-explorer", CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build());
        RetryRegistry retryRegistry = RetryRegistry.ofDefaults();
        retryRegistry.retry("mcp-tool", RetryConfig.custom().maxAttempts(1).build());
        McpToolCatalog catalog = new McpToolCatalog(List.of(client), ObservationRegistry.NOOP,
                circuitBreakerRegistry, retryRegistry, new SimpleMeterRegistry());

        assertThatThrownBy(() -> catalog.call("kafka-explorer", "kex_list_topics", Map.of()))
                .isInstanceOf(McpServerUnavailableException.class);
        assertThatThrownBy(() -> catalog.call("kafka-explorer", "kex_list_topics", Map.of()))
                .isInstanceOf(McpServerUnavailableException.class);

        clearInvocations(client);
        assertThatThrownBy(() -> catalog.call("kafka-explorer", "kex_list_topics", Map.of()))
                .isInstanceOf(McpServerUnavailableException.class);
        verify(client, never()).initialize();
    }

    @Test
    void isole_le_disjoncteur_par_connexion() {
        given(client.getClientInfo()).willReturn(new McpSchema.Implementation("kex-agent - kafka-explorer", "0.1.0"));
        given(client.isInitialized()).willReturn(false);
        given(client.initialize()).willThrow(new IllegalStateException("connection refused"));

        given(other.getClientInfo()).willReturn(new McpSchema.Implementation("kex-agent - autre", "0.1.0"));
        given(other.isInitialized()).willReturn(true);
        given(other.callTool(any(McpSchema.CallToolRequest.class))).willReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("ok")), false, null, null));

        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
        circuitBreakerRegistry.circuitBreaker("mcp-tool-kafka-explorer", CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build());
        RetryRegistry retryRegistry = RetryRegistry.ofDefaults();
        retryRegistry.retry("mcp-tool", RetryConfig.custom().maxAttempts(1).build());
        McpToolCatalog catalog = new McpToolCatalog(List.of(client, other), ObservationRegistry.NOOP,
                circuitBreakerRegistry, retryRegistry, new SimpleMeterRegistry());

        // Ouvre le disjoncteur de "kafka-explorer".
        assertThatThrownBy(() -> catalog.call("kafka-explorer", "kex_list_topics", Map.of()))
                .isInstanceOf(McpServerUnavailableException.class);
        assertThatThrownBy(() -> catalog.call("kafka-explorer", "kex_list_topics", Map.of()))
                .isInstanceOf(McpServerUnavailableException.class);

        // Une panne sur "kafka-explorer" ne doit pas faire échouer vite les appels vers "autre".
        McpToolResult result = catalog.call("autre", "kex_list_topics", Map.of());
        assertThat(result.error()).isFalse();
    }

    @Test
    void publie_une_jauge_de_disponibilite_par_connexion() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        given(client.getClientInfo()).willReturn(new McpSchema.Implementation("kex-agent - kafka-explorer", "0.1.0"));
        given(client.isInitialized()).willReturn(true);

        new McpToolCatalog(List.of(client), ObservationRegistry.NOOP, CircuitBreakerRegistry.ofDefaults(),
                RetryRegistry.ofDefaults(), meterRegistry);

        assertThat(meterRegistry.get("kex.mcp.server.up").tag("connection", "kafka-explorer").gauge().value())
                .isEqualTo(1.0);
    }

    @Test
    void refuse_une_commande_stdio_absente_de_l_allowlist() {
        RetryRegistry retryRegistry = RetryRegistry.ofDefaults();
        McpToolCatalog catalog = new McpToolCatalog(List.of(), ObservationRegistry.NOOP,
                CircuitBreakerRegistry.ofDefaults(), retryRegistry, new SimpleMeterRegistry(), new ObjectMapper(),
                new McpRuntimeProperties(".kex/test.enc", "", Duration.ofSeconds(1), 5, List.of("npx")));
        McpServerRegistration registration = new McpServerRegistration("shell", "STDIO", null, null, null,
                Map.of(), "sh", List.of("-c", "id"), Map.of(), true, Set.of(), Map.of());

        assertThatThrownBy(() -> catalog.test(registration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non autorisée");
    }

    @Test
    void refuse_deux_sources_d_authorization_http() {
        McpServerRegistration registration = new McpServerRegistration("double-auth", "HTTP",
                "https://mcp.example.net", "/mcp", "bearer", Map.of("Authorization", "Basic secret"),
                null, List.of(), Map.of(), true, Set.of(), Map.of());

        assertThatThrownBy(() -> catalog(true).test(registration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("soit le bearer");
    }

    @Test
    void agrege_les_metriques_par_connexion_et_par_outil() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meterRegistry));
        given(client.getClientInfo()).willReturn(new McpSchema.Implementation("kex-agent - kafka-explorer", "0.1.0"));
        given(client.isInitialized()).willReturn(true);
        given(client.callTool(any(McpSchema.CallToolRequest.class))).willReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("ok")), false, null, null));

        McpToolCatalog catalog = new McpToolCatalog(List.of(client), observationRegistry,
                CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults(), meterRegistry);
        catalog.call("kafka-explorer", "kex_list_topics", Map.of());
        catalog.call("kafka-explorer", "kex_list_topics", Map.of());

        assertThat(catalog.metrics()).singleElement().satisfies(metric -> {
            assertThat(metric.connection()).isEqualTo("kafka-explorer");
            assertThat(metric.tool()).isEqualTo("kex_list_topics");
            assertThat(metric.callCount()).isEqualTo(2);
            assertThat(metric.averageDurationMs()).isNotNull();
        });
    }
}
