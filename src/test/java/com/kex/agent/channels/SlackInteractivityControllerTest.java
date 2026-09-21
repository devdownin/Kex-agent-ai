// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.supervision.SupervisionService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Le clic d'un bouton posé par {@link SlackChannelAdapter}, jusqu'à l'appel sur
 * {@link SupervisionService}. Même structure que {@link InboundApprovalTest} : chaque verrou
 * vérifié seul, pas seulement le chemin nominal.
 */
class SlackInteractivityControllerTest {

    private static final String SECRET = "8f742231b10e8888abcd99yyyzzz85a";
    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final SupervisionService supervision = mock(SupervisionService.class);
    private final SlackRequestSignature signature = new SlackRequestSignature(SECRET, Duration.ofMinutes(5), clock);
    private final SlackInteractivityController controller = new SlackInteractivityController(supervision, signature,
            new ObjectMapper(), properties(Map.of("U123", "alice")));

    @Test
    void approuve_pour_l_operateur_declare() {
        String body = form(json("kex_approve", "d-1", "U123"));

        controller.interactivity(timestamp(), sign(timestamp(), body), body);

        verify(supervision).approve("d-1", "alice");
    }

    @Test
    void refuse_pour_le_bouton_refuser() {
        String body = form(json("kex_reject", "d-1", "U123"));

        controller.interactivity(timestamp(), sign(timestamp(), body), body);

        verify(supervision).reject("d-1", "Refusée depuis Slack", "alice");
    }

    @Test
    void rejette_une_signature_invalide() {
        String body = form(json("kex_approve", "d-1", "U123"));

        assertThatThrownBy(() -> controller.interactivity(timestamp(), sign(timestamp(), body + "x"), body))
                .isInstanceOf(InboundApprovalController.InboundRefusedException.class);

        verify(supervision, never()).approve(eq("d-1"), eq("alice"));
    }

    /** Signé ne veut pas dire autorisé : un identifiant Slack non déclaré est refusé. */
    @Test
    void rejette_un_utilisateur_slack_non_declare() {
        String body = form(json("kex_approve", "d-1", "U999"));

        assertThatThrownBy(() -> controller.interactivity(timestamp(), sign(timestamp(), body), body))
                .isInstanceOf(InboundApprovalController.InboundRefusedException.class);

        verify(supervision, never()).approve(eq("d-1"), eq("alice"));
    }

    @Test
    void rejette_une_charge_utile_sans_action() {
        String body = form("{\"user\":{\"id\":\"U123\"},\"actions\":[]}");

        assertThatThrownBy(() -> controller.interactivity(timestamp(), sign(timestamp(), body), body))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exige_un_secret_au_demarrage() {
        assertThatThrownBy(() -> new SlackRequestSignature("  ", Duration.ofMinutes(5), clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String timestamp() {
        return String.valueOf(NOW.getEpochSecond());
    }

    private static String json(String actionId, String decisionId, String userId) {
        return "{\"type\":\"block_actions\",\"user\":{\"id\":\"" + userId + "\"},"
                + "\"actions\":[{\"action_id\":\"" + actionId + "\",\"value\":\"" + decisionId + "\"}]}";
    }

    private static String form(String json) {
        return "payload=" + URLEncoder.encode(json, StandardCharsets.UTF_8);
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

    private static ChannelProperties properties(Map<String, String> operators) {
        return new ChannelProperties(true, "https://console.example.com", "https://hooks.slack.com/services/1",
                SECRET, "", new ChannelProperties.Email(false, "", java.util.List.of()),
                new ChannelProperties.Inbound(true, "autre-secret", Duration.ofMinutes(5), operators));
    }
}
