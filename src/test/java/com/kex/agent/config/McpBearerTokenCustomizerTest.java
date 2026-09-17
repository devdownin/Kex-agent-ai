// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Optional;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class McpBearerTokenCustomizerTest {

    private static final List<McpAuthProperties.BearerToken> TOKENS = List.of(
            new McpAuthProperties.BearerToken("http://localhost:8080", "secret"),
            new McpAuthProperties.BearerToken("https://vide.example.com", "  "));

    @Test
    void se_tait_sur_un_prefixe_sans_jeton_declare() {
        // La forme même d'un serveur MCP par défaut qui ne demande aucune authentification
        // (voir application.yml pour Kafka Explorer) : pas un avertissement à chaque démarrage.
        assertThat(collectLogs(TOKENS)).isEmpty();
    }

    @Test
    void avertit_sur_un_jeton_declare_sans_prefixe() {
        // Là, jamais posé nulle part : customize() ne l'applique qu'à une URL qui commence par
        // le préfixe, donc un jeton sans préfixe ne s'appliquera jamais à aucune requête.
        List<McpAuthProperties.BearerToken> tokens = List.of(
                new McpAuthProperties.BearerToken("  ", "orphelin"));

        assertThat(collectLogs(tokens)).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("url-prefix");
        });
    }

    private static List<ILoggingEvent> collectLogs(List<McpAuthProperties.BearerToken> tokens) {
        Logger logger = (Logger) LoggerFactory.getLogger(McpBearerTokenCustomizer.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            new McpBearerTokenCustomizer(tokens);
        }
        finally {
            logger.detachAppender(logs);
        }
        return logs.list;
    }

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
