package com.kex.agent.mcp;

import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpToolCatalogTest {

    @Mock
    McpSyncClient client;

    private McpToolCatalog catalog(boolean initialized) {
        given(client.getClientInfo()).willReturn(new McpSchema.Implementation("kex-agent - kafka-explorer", "0.1.0"));
        given(client.isInitialized()).willReturn(initialized);
        return new McpToolCatalog(List.of(client), ObservationRegistry.NOOP);
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
            assertThat(server.tools()).containsExactly(new McpToolInfo("kex_list_topics", "Liste les topics"));
        });
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
}
