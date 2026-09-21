// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.web.client.RestClient;

/**
 * Incoming webhooks need no bot token or inbound, impersonable callback.
 *
 * <p>{@code interactive} adds two buttons that post back to Slack's own interactivity mechanism —
 * {@link SlackInteractivityController} — rather than merely linking to the console. It requires
 * nothing beyond what an incoming webhook already has: no bot token, only the App's Interactivity
 * &amp; Shortcuts switched on with a Request URL. {@code false} keeps the adapter's original,
 * link-only behavior exactly as it was before this capability existed.
 */
public final class SlackChannelAdapter implements ChannelAdapter {
    private final RestClient client;
    private final String webhook;
    private final boolean interactive;

    public SlackChannelAdapter(RestClient client, String webhook, boolean interactive) {
        this.client = client;
        this.webhook = webhook;
        this.interactive = interactive;
    }

    @Override
    public String name() {
        return "slack";
    }

    static Map<String, Object> payload(ChannelMessage message, boolean interactive) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(Map.of("type", "header", "text", plain(message.subject().substring(0,
                Math.min(150, message.subject().length())))));
        // plain_text prevents model-supplied mentions or arbitrary Slack links from becoming actions.
        String body = message.body();
        for (int start = 0; start < body.length(); start += 2800) {
            blocks.add(Map.of("type", "section", "text", plain(body.substring(start,
                    Math.min(start + 2800, body.length())))));
        }
        if (message.expiresAt() != null) {
            blocks.add(Map.of("type", "context", "elements", List.of(plain("Expire : " + message.expiresAt()))));
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        if (message.actionUrl() != null) {
            actions.add(Map.of("type", "button",
                    "text", plain("Examiner dans Kex"), "url", message.actionUrl().toASCIIString()));
        }
        // decisionId conditionne aussi bien que interactive : un message générique (send()) n'en
        // porte pas, et rien à approuver n'a de sens pour lui même si Slack est configuré en retour.
        if (interactive && message.decisionId() != null) {
            actions.add(Map.of("type", "button", "style", "primary", "text", plain("Approuver"),
                    "action_id", "kex_approve", "value", message.decisionId()));
            actions.add(Map.of("type", "button", "style", "danger", "text", plain("Refuser"),
                    "action_id", "kex_reject", "value", message.decisionId()));
        }
        if (!actions.isEmpty()) {
            blocks.add(Map.of("type", "actions", "elements", actions));
        }
        return Map.of("text", "Notification Kex", "blocks", blocks, "unfurl_links", false, "unfurl_media", false);
    }

    private static Map<String, String> plain(String text) {
        return Map.of("type", "plain_text", "text", text);
    }

    @Override
    public void send(ChannelMessage message) {
        client.post().uri(webhook).body(payload(message, interactive)).retrieve().toBodilessEntity();
    }
}
