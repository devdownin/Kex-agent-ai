// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce qu'aucun test unitaire de {@link SlackInteractivityController} ne peut prouver : que Spring
 * livre bien le corps brut, exactement tel que Slack l'a envoyé, à travers la chaîne de filtres
 * réelle — sécurité comprise — pour un {@code Content-Type: application/x-www-form-urlencoded}.
 * C'est un cas connu où un filtre qui lit {@code getParameter()} avant le contrôleur consomme le
 * flux et vide {@code @RequestBody} : rien dans un appel direct à la méthode ne l'aurait révélé.
 *
 * <p>Un identifiant Slack non déclaré est le scénario choisi : il ne dépend d'aucun état
 * (contrairement à une décision réelle), et n'aboutit à ce {@code 403} précis que si la
 * signature — calculée sur le corps exact reçu — a d'abord été vérifiée avec succès. Un corps
 * tronqué ou réencodé en route ferait échouer la signature avant d'atteindre ce refus-là.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"kex.agent.channels.enabled=true",
                "kex.agent.channels.console-url=https://console.example.com",
                "kex.agent.channels.slack-webhook-url=https://hooks.slack.com/services/1",
                "kex.agent.channels.slack-signing-secret=8f742231b10e8888abcd99yyyzzz85a",
                "kex.agent.channels.inbound.enabled=true",
                "kex.agent.channels.inbound.secret=autre-secret-pour-la-route-generique",
                "kex.agent.channels.inbound.operators.U123=alice"})
@ActiveProfiles("test")
class SlackInteractivityWiringTest {

    private static final String SECRET = "8f742231b10e8888abcd99yyyzzz85a";

    @LocalServerPort
    int port;

    @Test
    void le_corps_brut_traverse_la_chaine_de_filtres_intact() throws IOException, InterruptedException {
        String json = "{\"type\":\"block_actions\",\"user\":{\"id\":\"U999\"},"
                + "\"actions\":[{\"action_id\":\"kex_approve\",\"value\":\"d-1\"}]}";
        String body = "payload=" + URLEncoder.encode(json, StandardCharsets.UTF_8);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/agent/channels/slack/interactivity"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-Slack-Request-Timestamp", timestamp)
                .header("X-Slack-Signature", sign(timestamp, body))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        assertThat(response.statusCode()).isEqualTo(403);
    }

    private static String sign(String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(("v0:" + timestamp + ":" + body).getBytes(StandardCharsets.UTF_8));
            return "v0=" + HexFormat.of().formatHex(raw);
        }
        catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
