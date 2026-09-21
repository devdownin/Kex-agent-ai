// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import java.net.URI;
import java.time.Instant;

/**
 * The action URL only opens the console; possessing it grants no approval authority.
 *
 * @param decisionId {@code null} for a free-form message ({@code send}); set only for a decision
 *                   approval, where a channel able to receive a signed reply (Slack's own
 *                   interactivity, not the console link) may offer to act on it directly. Carrying
 *                   this id is not itself authority — {@link SlackInteractivityController} still
 *                   verifies Slack's signature and resolves the clicking user against a declared
 *                   operator before calling anything.
 */
public record ChannelMessage(String subject, String body, URI actionUrl, Instant expiresAt, String decisionId) {
    public ChannelMessage {
        subject = bounded(subject, 200).replace('\r', ' ').replace('\n', ' ');
        body = bounded(body, 6000);
    }

    private static String bounded(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }
}
