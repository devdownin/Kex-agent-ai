// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L'algorithme que Slack documente pour vérifier ses requêtes : {@code v0:<horodatage>:<corps>},
 * rendu {@code v0=<hex>}. Différent de {@link InboundSignature}, qui n'a ni la version
 * d'algorithme dans la base signée ni ce préfixe dans le résultat — les confondre laisserait
 * passer une signature de l'un validée avec le secret de l'autre.
 */
class SlackRequestSignatureTest {

    private static final String SECRET = "8f742231b10e8888abcd99yyyzzz85a";
    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final SlackRequestSignature signature = new SlackRequestSignature(SECRET, Duration.ofMinutes(5), clock);

    /**
     * La forme d'un vrai appel Slack — un corps {@code application/x-www-form-urlencoded}
     * comme Slack le documente pour l'entrée classique, signature calculée avec le même
     * algorithme que {@link #accepte_une_signature_correctement_calculee} vérifie déjà seul :
     * ce test-ci porte sur la forme du corps, pas sur un nouveau calcul.
     */
    @Test
    void accepte_un_corps_de_formulaire_realiste() {
        SlackRequestSignature ancien = new SlackRequestSignature(SECRET, Duration.ofDays(3650),
                Clock.fixed(Instant.ofEpochSecond(1531420618L), ZoneOffset.UTC));
        String body = "token=xyzz0WbapA4vBCDEFasx0q6G&team_id=T1DC2JH3J&team_domain=testteamnow"
                + "&channel_id=G8PSS9T3V&channel_name=foobar&user_id=U2CERLKJA";

        assertThat(ancien.reject("1531420618", sign("1531420618", body), body)).isNull();
    }

    @Test
    void accepte_une_signature_correctement_calculee() {
        String body = "payload=%7B%22type%22%3A%22block_actions%22%7D";

        assertThat(signature.reject(timestamp(), sign(timestamp(), body), body)).isNull();
    }

    /**
     * Base signée sans version d'algorithme, résultat sans préfixe {@code v0=} — exactement la
     * forme d'{@link InboundSignature}. Confondre les deux laisserait une signature calculée pour
     * l'entrée générique passer ici, ou l'inverse.
     */
    @Test
    void rejette_une_signature_calculee_avec_l_algorithme_de_l_entree_generique() {
        String body = "payload=abc";
        String wrong = HexFormat.of().formatHex(hmac(timestamp() + "." + body));

        assertThat(signature.reject(timestamp(), wrong, body)).isNotNull();
    }

    @Test
    void rejette_un_horodatage_hors_fenetre() {
        String body = "payload=abc";
        String old = String.valueOf(NOW.minus(Duration.ofMinutes(30)).getEpochSecond());

        assertThat(signature.reject(old, sign(old, body), body)).isNotNull();
    }

    @Test
    void rejette_une_version_de_signature_inconnue() {
        String body = "payload=abc";

        assertThat(signature.reject(timestamp(), "v1=" + HexFormat.of().formatHex(hmac("v0:" + timestamp() + ":" + body)),
                body)).isNotNull();
    }

    @Test
    void exige_un_secret_au_demarrage() {
        assertThatThrownBy(() -> new SlackRequestSignature("  ", Duration.ofMinutes(5), clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String timestamp() {
        return String.valueOf(NOW.getEpochSecond());
    }

    private static String sign(String timestamp, String body) {
        return "v0=" + HexFormat.of().formatHex(hmac("v0:" + timestamp + ":" + body));
    }

    private static byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        }
        catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
