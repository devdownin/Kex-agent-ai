package com.kex.agent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class McpToolCatalogTest {

    @Mock
    McpSyncClient client;

    @Test
    void expose_les_outils_des_serveurs_initialises() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("read_file")
                .description("Lit un fichier")
                .inputSchema(Map.of("type", "object"))
                .build();
        given(client.getServerInfo()).willReturn(new McpSchema.Implementation("filesystem", "1.2.3"));
        given(client.getCurrentInitializationResult()).willReturn(null);
        given(client.isInitialized()).willReturn(true);
        given(client.listTools()).willReturn(new McpSchema.ListToolsResult(List.of(tool), null));

        List<McpServerInfo> servers = new McpToolCatalog(List.of(client)).servers();

        assertThat(servers).singleElement().satisfies(server -> {
            assertThat(server.name()).isEqualTo("filesystem");
            assertThat(server.version()).isEqualTo("1.2.3");
            assertThat(server.initialized()).isTrue();
            assertThat(server.tools()).containsExactly(new McpToolInfo("read_file", "Lit un fichier"));
        });
    }

    @Test
    void appelle_l_outil_du_serveur_cible() {
        given(client.getServerInfo()).willReturn(new McpSchema.Implementation("filesystem", "1.2.3"));
        given(client.isInitialized()).willReturn(true);
        given(client.callTool(new McpSchema.CallToolRequest("read_file", Map.of("path", "/tmp/a.txt"))))
                .willReturn(new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("contenu")), false, null, null));

        McpToolResult result = new McpToolCatalog(List.of(client))
                .call("filesystem", "read_file", Map.of("path", "/tmp/a.txt"));

        assertThat(result.error()).isFalse();
        assertThat(result.content()).containsExactly("contenu");
    }

    @Test
    void remonte_l_echec_signale_par_l_outil() {
        given(client.getServerInfo()).willReturn(new McpSchema.Implementation("filesystem", "1.2.3"));
        given(client.isInitialized()).willReturn(true);
        given(client.callTool(org.mockito.ArgumentMatchers.any(McpSchema.CallToolRequest.class)))
                .willReturn(new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("ENOENT")), true, null, null));

        McpToolResult result = new McpToolCatalog(List.of(client)).call("filesystem", "read_file", null);

        assertThat(result.error()).isTrue();
        assertThat(result.content()).containsExactly("ENOENT");
    }

    @Test
    void rejette_un_serveur_inconnu() {
        given(client.getServerInfo()).willReturn(new McpSchema.Implementation("filesystem", "1.2.3"));
        given(client.isInitialized()).willReturn(true);

        McpToolCatalog catalog = new McpToolCatalog(List.of(client));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> catalog.call("absent", "read_file", Map.of()))
                .isInstanceOf(UnknownMcpServerException.class)
                .hasMessageContaining("absent");
    }

    @Test
    void n_interroge_pas_un_serveur_non_initialise() {
        given(client.getServerInfo()).willReturn(new McpSchema.Implementation("down", "0.0.1"));
        given(client.isInitialized()).willReturn(false);

        List<McpServerInfo> servers = new McpToolCatalog(List.of(client)).servers();

        assertThat(servers).singleElement().satisfies(server -> {
            assertThat(server.initialized()).isFalse();
            assertThat(server.tools()).isEmpty();
        });
    }

    @Test
    void degrade_proprement_si_le_listing_echoue() {
        given(client.getServerInfo()).willReturn(new McpSchema.Implementation("flaky", "1.0.0"));
        given(client.isInitialized()).willReturn(true);
        given(client.listTools()).willThrow(new IllegalStateException("transport closed"));

        List<McpServerInfo> servers = new McpToolCatalog(List.of(client)).servers();

        assertThat(servers).singleElement().extracting(McpServerInfo::tools).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.LIST).isEmpty();
    }
}
