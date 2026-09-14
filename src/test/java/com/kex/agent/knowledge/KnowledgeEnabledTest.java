// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.knowledge;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Le profil activé doit réellement brancher l'advisor sur le ChatClient, pas seulement exister. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "kex.agent.api-key=secret",
        "kex.agent.rate-limit.enabled=false",
        "kex.agent.knowledge.enabled=true"
})
@ActiveProfiles("test")
class KnowledgeEnabledTest {

    @TestConfiguration
    static class Embeddings {
        @Bean
        EmbeddingModel embeddingModel() {
            return new DeterministicEmbeddingModel();
        }
    }

    @Autowired
    ApplicationContext context;

    @LocalServerPort
    int port;

    @Test
    void branche_l_advisor_de_connaissance() {
        assertThat(context.getBeanNamesForType(QuestionAnswerAdvisor.class)).isNotEmpty();
        assertThat(context.getBeanNamesForType(KnowledgeService.class)).isNotEmpty();
    }

    @Test
    void expose_l_ingestion_derriere_le_bearer() throws Exception {
        assertThat(post("[{\"text\":\"La rétention des topics demo est de 7 jours.\"}]", null)).isEqualTo(401);
        assertThat(post("[{\"text\":\"La rétention des topics demo est de 7 jours.\"}]", "Bearer secret"))
                .isEqualTo(201);

        assertThat(search("retention topic")).contains("rétention");
    }

    private int post(String body, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + "/api/agent/knowledge"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    private String search(String query) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/agent/knowledge?query=" + java.net.URLEncoder.encode(query,
                        java.nio.charset.StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer secret")
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        }
    }
}
