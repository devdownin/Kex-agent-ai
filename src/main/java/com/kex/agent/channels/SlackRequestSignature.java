// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Vérifie qu'une requête d'interactivité vient bien de Slack, selon l'algorithme que Slack
 * documente lui-même — distinct de {@link InboundSignature} : la base signée est
 * {@code v0:<horodatage>:<corps>}, préfixée d'une version d'algorithme, et rendue avec le préfixe
 * {@code v0=} plutôt qu'un hexadécimal nu. Ce n'est pas le même secret non plus : celui-ci est émis
 * par Slack à la création de l'App, pas choisi par l'exploitant.
 *
 * <p>Même fenêtre de rejeu que l'entrée générique, pour la même raison : une requête signée
 * interceptée resterait rejouable indéfiniment sans elle.
 */
final class SlackRequestSignature {
    private static final String ALGORITHM = "HmacSHA256";
    private static final String VERSION = "v0";

    private final byte[] secret;
    private final Duration tolerance;
    private final Clock clock;

    SlackRequestSignature(String secret, Duration tolerance, Clock clock) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("channels.slack-signing-secret ne peut pas être vide");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.tolerance = tolerance;
        this.clock = clock;
    }

    /** @return {@code null} si la requête est acceptée, sinon la raison du refus */
    String reject(String timestamp, String signature, String body) {
        if (timestamp == null || timestamp.isBlank() || signature == null || signature.isBlank()) {
            return "Signature ou horodatage absent";
        }
        Instant signedAt;
        try {
            signedAt = Instant.ofEpochSecond(Long.parseLong(timestamp.strip()));
        }
        catch (NumberFormatException ex) {
            return "Horodatage illisible";
        }
        if (Duration.between(signedAt, clock.instant()).abs().compareTo(tolerance) > 0) {
            return "Horodatage hors de la fenêtre acceptée";
        }
        String candidate = signature.strip();
        if (!candidate.regionMatches(true, 0, VERSION + "=", 0, VERSION.length() + 1)) {
            return "Version de signature inconnue";
        }
        byte[] expected = sign(VERSION + ":" + timestamp.strip() + ":" + body);
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(candidate.substring(VERSION.length() + 1).toLowerCase(Locale.ROOT));
        }
        catch (IllegalArgumentException ex) {
            return "Signature illisible";
        }
        return MessageDigest.isEqual(expected, provided) ? null : "Signature invalide";
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        }
        catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 indisponible", ex);
        }
    }
}
