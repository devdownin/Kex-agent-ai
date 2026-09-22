// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelStatusControllerTest {

    @Test
    void ne_rend_aucun_canal_quand_rien_n_est_configure() {
        ChannelsStatus status = new ChannelStatusController(properties("", "", "", "",
                false, List.of(), false, false)).status();

        assertThat(status).isEqualTo(new ChannelsStatus(false, false, false, false, 0, false, false));
    }

    @Test
    void distingue_slack_avec_et_sans_boutons_interactifs() {
        ChannelsStatus sansBoutons = new ChannelStatusController(properties("https://hooks.example.com/a",
                "", "", "", false, List.of(), false, false)).status();
        assertThat(sansBoutons.slack()).isTrue();
        assertThat(sansBoutons.slackInteractiveButtons()).isFalse();

        ChannelsStatus avecBoutons = new ChannelStatusController(properties("https://hooks.example.com/a",
                "un-secret", "", "", false, List.of(), true, false)).status();
        assertThat(avecBoutons.slack()).isTrue();
        assertThat(avecBoutons.slackInteractiveButtons()).isTrue();
    }

    @Test
    void rend_teams_email_et_l_approbation_entrante_independamment() {
        ChannelsStatus status = new ChannelStatusController(properties("", "", "https://outlook.example.com/webhook",
                "", true, List.of("astreinte@example.com", "backup@example.com"), false, false)).status();

        assertThat(status.teams()).isTrue();
        assertThat(status.email()).isTrue();
        assertThat(status.emailRecipients()).isEqualTo(2);
        assertThat(status.inboundApproval()).isTrue();
        assertThat(status.consoleUrlConfigured()).isFalse();
    }

    @Test
    void rend_l_url_de_console_configuree() {
        ChannelsStatus status = new ChannelStatusController(new ChannelProperties(true,
                "https://console.example.com", "", "", "",
                new ChannelProperties.Email(false, "", List.of()),
                new ChannelProperties.Inbound(false, "", Duration.ofMinutes(5), Map.of()))).status();

        assertThat(status.consoleUrlConfigured()).isTrue();
    }

    private static ChannelProperties properties(String slackWebhookUrl, String slackSigningSecret,
            String teamsWebhookUrl, String emailFrom, boolean emailEnabled, List<String> emailRecipients,
            boolean inboundEnabled, boolean consoleUrlSet) {
        return new ChannelProperties(true, consoleUrlSet ? "https://console.example.com" : "",
                slackWebhookUrl, slackSigningSecret, teamsWebhookUrl,
                new ChannelProperties.Email(emailEnabled, emailFrom, emailRecipients),
                new ChannelProperties.Inbound(inboundEnabled, "", Duration.ofMinutes(5), Map.of()));
    }
}
