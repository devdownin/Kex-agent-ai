// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * La seule route de l'API qui décide sans bearer. Chaque verrou est vérifié seul : un test qui ne
 * couvrirait que le chemin nominal laisserait passer une route ouverte à qui sait forger un corps.
 */
class InboundApprovalTest {

    private static final String SECRET = "secret-partagé";
    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final SupervisionService supervision = mock(SupervisionService.class);
    private final InboundSignature signature = new InboundSignature(SECRET, Duration.ofMinutes(5), clock);
    private final InboundApprovalController controller = new InboundApprovalController(supervision, signature,
            new ObjectMapper(), properties(Map.of("U123", "alice")));

    @Test
    void approuve_pour_l_operateur_declare() {
        String body = "{\"sender\":\"U123\",\"decisionId\":\"d-1\",\"approve\":true}";

        controller.callback(timestamp(), sign(timestamp(), body), body);

        verify(supervision).approve("d-1", "alice");
    }

    @Test
    void refuse_avec_le_motif_transmis() {
        String body = "{\"sender\":\"U123\",\"decisionId\":\"d-1\",\"approve\":false,\"reason\":\"pas maintenant\"}";

        controller.callback(timestamp(), sign(timestamp(), body), body);

        verify(supervision).reject("d-1", "pas maintenant", "alice");
    }

    @Test
    void rejette_une_signature_invalide() {
        String body = "{\"sender\":\"U123\",\"decisionId\":\"d-1\",\"approve\":true}";

        assertThatThrownBy(() -> controller.callback(timestamp(), sign(timestamp(), body + " "), body))
                .isInstanceOf(InboundApprovalController.InboundRefusedException.class);

        verify(supervision, never()).approve(eq("d-1"), eq("alice"));
    }

    /** Sans fenêtre d'horodatage, une requête signée capturée resterait rejouable pour toujours. */
    @Test
    void rejette_un_horodatage_hors_fenetre() {
        String body = "{\"sender\":\"U123\",\"decisionId\":\"d-1\",\"approve\":true}";
        String old = String.valueOf(NOW.minus(Duration.ofMinutes(30)).getEpochSecond());

        assertThatThrownBy(() -> controller.callback(old, sign(old, body), body))
                .isInstanceOf(InboundApprovalController.InboundRefusedException.class);
    }

    /** Signé ne veut pas dire autorisé : sans acteur déclaré, l'audit ne saurait pas qui a tranché. */
    @Test
    void rejette_un_expediteur_non_declare() {
        String body = "{\"sender\":\"U999\",\"decisionId\":\"d-1\",\"approve\":true}";

        assertThatThrownBy(() -> controller.callback(timestamp(), sign(timestamp(), body), body))
                .isInstanceOf(InboundApprovalController.InboundRefusedException.class);

        verify(supervision, never()).approve(eq("d-1"), eq("alice"));
    }

    @Test
    void rejette_un_corps_incomplet_ou_illisible() {
        String incomplete = "{\"sender\":\"U123\"}";
        assertThatThrownBy(() -> controller.callback(timestamp(), sign(timestamp(), incomplete), incomplete))
                .isInstanceOf(IllegalArgumentException.class);

        String garbage = "pas du json";
        assertThatThrownBy(() -> controller.callback(timestamp(), sign(timestamp(), garbage), garbage))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejette_une_signature_ou_un_horodatage_absent() {
        String body = "{\"sender\":\"U123\",\"decisionId\":\"d-1\",\"approve\":true}";

        assertThatThrownBy(() -> controller.callback(null, sign(timestamp(), body), body))
                .isInstanceOf(InboundApprovalController.InboundRefusedException.class);
        assertThatThrownBy(() -> controller.callback(timestamp(), null, body))
                .isInstanceOf(InboundApprovalController.InboundRefusedException.class);
        assertThatThrownBy(() -> controller.callback("pas-un-nombre", sign(timestamp(), body), body))
                .isInstanceOf(InboundApprovalController.InboundRefusedException.class);
        assertThatThrownBy(() -> controller.callback(timestamp(), "zz-pas-du-hex", body))
                .isInstanceOf(InboundApprovalController.InboundRefusedException.class);
    }

    /** La route refuse de démarrer sans secret plutôt que d'accepter n'importe quel appelant. */
    @Test
    void exige_un_secret_au_demarrage() {
        assertThatThrownBy(() -> new InboundSignature("  ", Duration.ofMinutes(5), clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String timestamp() {
        return String.valueOf(NOW.getEpochSecond());
    }

    private static String sign(String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        }
        catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ChannelProperties properties(Map<String, String> operators) {
        return new ChannelProperties(true, "https://console.example.com", "", "",
                new ChannelProperties.Email(false, "", java.util.List.of()),
                new ChannelProperties.Inbound(true, SECRET, Duration.ofMinutes(5), operators));
    }

    @Test
    void la_configuration_refuse_un_canal_entrant_sans_operateur() {
        ChannelConfig config = new ChannelConfig();

        assertThat(config).isNotNull();
        assertThatThrownBy(() -> config.inboundSignature(properties(Map.of()), clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("opérateur");
    }
}
