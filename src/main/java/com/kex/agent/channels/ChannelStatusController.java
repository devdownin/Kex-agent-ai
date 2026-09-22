// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seule lecture de {@link ChannelProperties} exposée à la console — sans elle, savoir quels canaux
 * sont actifs exige de relire {@code application.yml} sur la machine qui l'a déployé. Toujours
 * disponible, contrairement au reste du paquet : {@link ChannelProperties} vient d'un
 * {@code @ConfigurationPropertiesScan} global, pas d'un bean conditionné par
 * {@code channels.enabled}, donc « rien n'est configuré » est une réponse normale, jamais un 404.
 */
@RestController
@RequestMapping("/api/agent/channels")
class ChannelStatusController {
    private final ChannelProperties properties;

    ChannelStatusController(ChannelProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/status")
    ChannelsStatus status() {
        boolean slack = present(properties.slackWebhookUrl());
        return new ChannelsStatus(slack, slack && present(properties.slackSigningSecret()),
                present(properties.teamsWebhookUrl()), properties.email().enabled(),
                properties.email().recipients().size(), properties.inbound().enabled(),
                present(properties.consoleUrl()));
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
