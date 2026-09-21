// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Vérifie qu'un appel entrant vient bien de la messagerie configurée. Le corps brut est signé avec
 * l'horodatage : signer le seul corps rendrait une requête interceptée rejouable pour toujours, et
 * signer le seul horodatage laisserait réécrire la décision visée.
 *
 * <p>La comparaison passe par {@link MessageDigest#isEqual} : un {@code equals} sur chaîne s'arrête
 * au premier caractère différent, et ce temps de réponse suffit à reconstruire une signature octet
 * par octet.
 */
final class InboundSignature {
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;
    private final Duration tolerance;
    private final Clock clock;

    InboundSignature(String secret, Duration tolerance, Clock clock) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("channels.inbound.enabled exige un secret");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.tolerance = tolerance;
        this.clock = clock;
    }

    /** @return {@code null} si l'appel est accepté, sinon la raison du refus */
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
        byte[] expected = sign(timestamp.strip() + "." + body);
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signature.strip().toLowerCase(java.util.Locale.ROOT));
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
