package com.kex.agent.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class McpBearerTokenCustomizerTest {

    private static final List<McpAuthProperties.BearerToken> TOKENS = List.of(
            new McpAuthProperties.BearerToken("http://localhost:8080", "secret"),
            new McpAuthProperties.BearerToken("https://vide.example.com", "  "));

    @Test
    void ajoute_le_jeton_sur_le_serveur_declare() {
        assertThat(authorizationFor("http://localhost:8080/mcp")).contains("Bearer secret");
    }

    @Test
    void ne_diffuse_pas_le_jeton_vers_un_autre_serveur() {
        assertThat(authorizationFor("https://autre.example.com/mcp")).isEmpty();
    }

    @Test
    void ignore_une_entree_sans_jeton() {
        assertThat(authorizationFor("https://vide.example.com/mcp")).isEmpty();
    }

    private static Optional<String> authorizationFor(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
        new McpBearerTokenCustomizer(TOKENS).customize(builder, "POST", URI.create(url), null, null);
        return builder.build().headers().firstValue("Authorization");
    }
}
