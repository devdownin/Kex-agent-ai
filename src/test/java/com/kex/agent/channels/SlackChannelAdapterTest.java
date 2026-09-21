// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les boutons Approuver/Refuser ne sont posés que quand les deux conditions se rejoignent :
 * l'App Slack sait recevoir un clic ({@code interactive}) et le message porte une décision à
 * trancher ({@code decisionId}). Un message générique (`send()`) n'a jamais de decisionId, même
 * si Slack est configuré en retour — rien à approuver n'a de sens pour lui.
 */
class SlackChannelAdapterTest {

    @Test
    void sans_interactivite_seul_le_lien_vers_la_console_apparait() {
        ChannelMessage message = approval();

        Map<String, Object> payload = SlackChannelAdapter.payload(message, false);

        assertThat(actionTexts(payload)).containsExactly("Examiner dans Kex");
    }

    @Test
    void avec_interactivite_une_decision_gagne_les_boutons_approuver_et_refuser() {
        ChannelMessage message = approval();

        Map<String, Object> payload = SlackChannelAdapter.payload(message, true);

        assertThat(actionTexts(payload)).containsExactly("Examiner dans Kex", "Approuver", "Refuser");
    }

    /** Un message générique n'a rien à approuver, même si Slack sait recevoir un clic. */
    @Test
    void un_message_generique_ne_gagne_jamais_de_boutons_de_decision() {
        ChannelMessage message = new ChannelMessage("Incident", "Détails", URI.create("https://action.example"),
                null, null);

        Map<String, Object> payload = SlackChannelAdapter.payload(message, true);

        assertThat(actionTexts(payload)).containsExactly("Examiner dans Kex");
    }

    /** La valeur du bouton porte l'identifiant que l'interactivité renverra au clic. */
    @Test
    void les_boutons_de_decision_portent_l_identifiant_de_la_decision() {
        Map<String, Object> payload = SlackChannelAdapter.payload(approval(), true);

        List<Map<String, Object>> actions = actions(payload);
        Map<String, Object> approve = actions.stream()
                .filter(action -> "kex_approve".equals(action.get("action_id"))).findFirst().orElseThrow();
        Map<String, Object> reject = actions.stream()
                .filter(action -> "kex_reject".equals(action.get("action_id"))).findFirst().orElseThrow();
        assertThat(approve.get("value")).isEqualTo("d-1");
        assertThat(reject.get("value")).isEqualTo("d-1");
    }

    private static ChannelMessage approval() {
        return new ChannelMessage("Validation requise", "Redémarrer le consumer",
                URI.create("https://console.example.com#/decisions?decision=d-1"),
                Instant.parse("2026-09-21T12:00:00Z"), "d-1");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> actions(Map<String, Object> payload) {
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) payload.get("blocks");
        return blocks.stream()
                .filter(block -> "actions".equals(block.get("type")))
                .flatMap(block -> ((List<Map<String, Object>>) block.get("elements")).stream())
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> actionTexts(Map<String, Object> payload) {
        return actions(payload).stream()
                .map(action -> (String) ((Map<String, Object>) action.get("text")).get("text"))
                .toList();
    }
}
