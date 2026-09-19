// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import com.kex.agent.supervision.Decision;
import com.kex.agent.supervision.DecisionStatus;

/** Each destination fails independently. Never expose webhook URLs or SMTP exception details. */
public final class ChannelNotifier {
    private final List<ChannelAdapter> adapters;
    private final URI console;
    private final Clock clock;

    public ChannelNotifier(List<ChannelAdapter> adapters, String consoleUrl, Clock clock) {
        this.adapters = List.copyOf(adapters);
        this.console = consoleUrl == null || consoleUrl.isBlank() ? null : validateConsole(consoleUrl);
        this.clock = clock;
    }

    public Optional<String> send(String subject, String body) {
        return deliver(new ChannelMessage(subject, body, null, null));
    }

    public Optional<String> approval(Decision decision) {
        if (decision.status() != DecisionStatus.PENDING_APPROVAL
                || decision.expiresAt() == null || !decision.expiresAt().isAfter(clock.instant())) {
            return Optional.of("Demande absente ou expirée");
        }
        if (console == null) {
            return Optional.of("URL de la console non configurée");
        }
        URI link = URI.create(console.toASCIIString() + "#/decisions?decision="
                + URLEncoder.encode(decision.id(), StandardCharsets.UTF_8));
        return deliver(new ChannelMessage("Validation requise — " + decision.processName(),
                decision.action() + "\n" + decision.context() + "\nImpact : " + decision.estimatedImpact()
                        + "\nConnectez-vous avec votre identité opérateur pour approuver ou rejeter.",
                link, decision.expiresAt()));
    }

    private Optional<String> deliver(ChannelMessage message) {
        if (adapters.isEmpty()) {
            return Optional.of("Aucun canal configuré");
        }
        java.util.ArrayList<String> failures = new java.util.ArrayList<>();
        for (ChannelAdapter adapter : adapters) {
            try {
                adapter.send(message);
            }
            catch (RuntimeException ex) {
                failures.add(adapter.name());
            }
        }
        return failures.isEmpty() ? Optional.empty()
                : Optional.of("Échec de livraison : " + String.join(", ", failures));
    }

    static URI validateConsole(String value) {
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("console-url doit être une URL HTTPS sans identifiants, query ou fragment");
        }
        return uri;
    }
}
