// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.client.RestClient;

/** Adaptive Card payload for a Teams Workflows webhook. */
public final class TeamsChannelAdapter implements ChannelAdapter {
    private final RestClient client;
    private final String webhook;

    public TeamsChannelAdapter(RestClient client, String webhook) {
        this.client = client;
        this.webhook = webhook;
    }

    @Override
    public String name() {
        return "teams";
    }

    static Map<String, Object> payload(ChannelMessage message) {
        List<Map<String, Object>> body = new ArrayList<>();
        body.add(Map.of("type", "TextBlock", "text", plain(message.subject()), "weight", "Bolder", "wrap", true));
        body.add(Map.of("type", "TextBlock", "text", plain(message.body()), "wrap", true));
        if (message.expiresAt() != null) {
            body.add(Map.of("type", "TextBlock", "text", "Expire : " + message.expiresAt(), "wrap", true));
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "AdaptiveCard");
        card.put("version", "1.4");
        card.put("body", body);
        if (message.actionUrl() != null) {
            card.put("actions", List.of(Map.of("type", "Action.OpenUrl", "title", "Examiner dans Kex",
                    "url", message.actionUrl().toASCIIString())));
        }
        return Map.of("type", "message", "attachments", List.of(Map.of("contentType",
                "application/vnd.microsoft.card.adaptive", "contentUrl", "", "content", card)));
    }

    private static String plain(String text) {
        return text.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
                .replace("*", "\\*").replace("_", "\\_").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public void send(ChannelMessage message) {
        client.post().uri(webhook).body(payload(message)).retrieve().toBodilessEntity();
    }
}
