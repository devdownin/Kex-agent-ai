// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.web.client.RestClient;

/** Incoming webhooks need no bot token or inbound, impersonable callback. */
public final class SlackChannelAdapter implements ChannelAdapter {
    private final RestClient client;
    private final String webhook;

    public SlackChannelAdapter(RestClient client, String webhook) {
        this.client = client;
        this.webhook = webhook;
    }

    @Override
    public String name() {
        return "slack";
    }

    static Map<String, Object> payload(ChannelMessage message) {
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
        if (message.actionUrl() != null) {
            blocks.add(Map.of("type", "actions", "elements", List.of(Map.of("type", "button",
                    "text", plain("Examiner dans Kex"), "url", message.actionUrl().toASCIIString()))));
        }
        return Map.of("text", "Notification Kex", "blocks", blocks, "unfurl_links", false, "unfurl_media", false);
    }

    private static Map<String, String> plain(String text) {
        return Map.of("type", "plain_text", "text", text);
    }

    @Override
    public void send(ChannelMessage message) {
        client.post().uri(webhook).body(payload(message)).retrieve().toBodilessEntity();
    }
}
