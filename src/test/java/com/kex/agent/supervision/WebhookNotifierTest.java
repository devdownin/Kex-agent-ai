// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/** Contre un vrai serveur HTTP, comme {@code LlmCatalogServiceTest} : un client moqué ne prouverait rien. */
class WebhookNotifierTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String start(int status) throws IOException {
        List<String> bodies = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            try (var out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        this.receivedBodies = bodies;
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private List<String> receivedBodies;

    @Test
    void reussit_contre_un_webhook_qui_repond() throws IOException {
        String url = start(200);
        WebhookNotifier notifier = new WebhookNotifier(RestClient.builder(),
                new NotifyProperties(url, ""));

        Optional<String> failure = notifier.send("Retard détecté", "12 421 messages en attente");

        assertThat(failure).isEmpty();
        assertThat(receivedBodies).singleElement().asString()
                .contains("Retard détecté").contains("12 421 messages en attente");
    }

    @Test
    void signale_un_webhook_qui_refuse() throws IOException {
        String url = start(500);
        WebhookNotifier notifier = new WebhookNotifier(RestClient.builder(),
                new NotifyProperties(url, ""));

        assertThat(notifier.send("Retard détecté", "détail")).isPresent();
    }

    @Test
    void signale_l_absence_de_configuration_sans_appel_reseau() {
        WebhookNotifier notifier = new WebhookNotifier(RestClient.builder(), new NotifyProperties("", ""));

        assertThat(notifier.send("Retard détecté", "détail"))
                .contains("Aucun webhook de notification configuré (kex.agent.supervision.notify.webhook-url)");
    }
}
