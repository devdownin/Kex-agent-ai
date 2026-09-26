// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("kex.agent.gmail")
public record GmailCommandProperties(@DefaultValue("false") boolean enabled,
                                     @DefaultValue("") String username,
                                     @DefaultValue("") String appPassword,
                                     @DefaultValue("") String from,
                                     @DefaultValue Set<String> allowedSenders,
                                     @DefaultValue Set<String> readOnlyTools,
                                     @DefaultValue("1m") Duration pollInterval) {
    public GmailCommandProperties {
        allowedSenders = allowedSenders == null ? Set.of() : Set.copyOf(allowedSenders);
        readOnlyTools = readOnlyTools == null ? Set.of() : Set.copyOf(readOnlyTools);
        if (enabled && (username.isBlank() || appPassword.isBlank() || from.isBlank()
                || allowedSenders.isEmpty() || pollInterval.isNegative() || pollInterval.isZero())) {
            throw new IllegalArgumentException("gmail exige username, app-password, from, allowed-senders et poll-interval positif");
        }
    }
}
