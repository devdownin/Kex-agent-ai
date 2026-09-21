// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "kex.agent.channels", name = "enabled", havingValue = "true")
class ChannelConfig {
    @Bean
    ChannelNotifier channelNotifier(ChannelProperties properties, RestClient.Builder builder,
                                    ObjectProvider<JavaMailSender> mail, Clock clock) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        RestClient client = builder.requestFactory(factory).build();
        List<ChannelAdapter> adapters = new ArrayList<>();
        if (present(properties.slackWebhookUrl())) {
            validateWebhook(properties.slackWebhookUrl());
            adapters.add(new SlackChannelAdapter(client, properties.slackWebhookUrl()));
        }
        if (present(properties.teamsWebhookUrl())) {
            validateWebhook(properties.teamsWebhookUrl());
            adapters.add(new TeamsChannelAdapter(client, properties.teamsWebhookUrl()));
        }
        if (properties.email().enabled()) {
            if (!present(properties.email().from()) || properties.email().recipients().isEmpty()) {
                throw new IllegalArgumentException("Le canal e-mail exige from et recipients");
            }
            JavaMailSender sender = mail.getIfAvailable();
            if (sender == null) {
                throw new IllegalArgumentException("Le canal e-mail exige spring.mail.host");
            }
            adapters.add(new EmailChannelAdapter(sender, properties.email()));
        }
        if (adapters.isEmpty()) {
            throw new IllegalArgumentException("channels.enabled exige au moins une destination");
        }
        return new ChannelNotifier(adapters, properties.consoleUrl(), clock);
    }

    /**
     * Le secret est exigé ici, au démarrage, et pas au premier appel : une route ouverte qui
     * n'apprend qu'elle ne sait pas vérifier qu'au moment où on l'appelle a déjà accepté la requête.
     */
    @Bean
    @ConditionalOnProperty(prefix = "kex.agent.channels.inbound", name = "enabled", havingValue = "true")
    InboundSignature inboundSignature(ChannelProperties properties, Clock clock) {
        if (properties.inbound().operators().isEmpty()) {
            throw new IllegalArgumentException("channels.inbound.enabled exige au moins un opérateur déclaré");
        }
        return new InboundSignature(properties.inbound().secret(), properties.inbound().tolerance(), clock);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static void validateWebhook(String value) {
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Le webhook doit utiliser HTTPS sans identifiants ni fragment");
        }
    }
}
