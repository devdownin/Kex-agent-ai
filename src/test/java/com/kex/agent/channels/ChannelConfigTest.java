// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ChannelConfigTest {

    private static final ChannelProperties.Inbound NO_INBOUND =
            new ChannelProperties.Inbound(false, "", java.time.Duration.ofMinutes(5), java.util.Map.of());

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneId.of("UTC"));

    @Test
    void creates_channel_notifier_with_slack_teams_and_email() {
        ChannelProperties properties = new ChannelProperties(true,
                "https://console.example.com",
                "https://hooks.slack.com/services/1",
                "",
                "https://outlook.office.com/webhook/1",
                new ChannelProperties.Email(true, "from@example.com", List.of("to@example.com")), NO_INBOUND);

        RestClient.Builder builder = RestClient.builder();
        JavaMailSender mailSender = mock(JavaMailSender.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> mailProvider = mock(ObjectProvider.class);
        given(mailProvider.getIfAvailable()).willReturn(mailSender);

        ChannelConfig config = new ChannelConfig();
        ChannelNotifier notifier = config.channelNotifier(properties, builder, mailProvider, clock);

        assertThat(notifier).isNotNull();
    }

    @Test
    void rejects_invalid_webhook_url() {
        ChannelConfig config = new ChannelConfig();
        RestClient.Builder builder = RestClient.builder();
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> mailProvider = mock(ObjectProvider.class);

        ChannelProperties invalidSlack = new ChannelProperties(true, "https://console.example.com", "http://insecure.slack.com", "", "",
                new ChannelProperties.Email(false, "", List.of()), NO_INBOUND);

        assertThatThrownBy(() -> config.channelNotifier(invalidSlack, builder, mailProvider, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_empty_adapters() {
        ChannelConfig config = new ChannelConfig();
        RestClient.Builder builder = RestClient.builder();
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> mailProvider = mock(ObjectProvider.class);

        ChannelProperties empty = new ChannelProperties(true, "https://console.example.com", "", "", "",
                new ChannelProperties.Email(false, "", List.of()), NO_INBOUND);

        assertThatThrownBy(() -> config.channelNotifier(empty, builder, mailProvider, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
