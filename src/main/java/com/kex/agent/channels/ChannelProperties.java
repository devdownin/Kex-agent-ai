// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Destinations configured by the operator, never supplied by model output. */
@ConfigurationProperties("kex.agent.channels")
public record ChannelProperties(@DefaultValue("false") boolean enabled,
                                @DefaultValue("") String consoleUrl,
                                @DefaultValue("") String slackWebhookUrl,
                                @DefaultValue("") String teamsWebhookUrl,
                                @DefaultValue Email email) {
    public record Email(@DefaultValue("false") boolean enabled,
                        @DefaultValue("") String from,
                        @DefaultValue List<String> recipients) {
        public Email {
            recipients = recipients == null ? List.of() : List.copyOf(recipients);
        }
    }
}
