// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

/** Extrait structuré borné, masqué avant persistance et diffusion dans le suivi. */
final class ToolResultEvidence {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SECRET = Pattern.compile(
            "password|passwd|secret|token|authorization|api.?key|credential|cookie|private.?key",
            Pattern.CASE_INSENSITIVE);

    private ToolResultEvidence() { }

    static String sanitize(Object result) {
        if (!(result instanceof String text) || text.length() > 4000) return null;
        try {
            JsonNode node = JSON.readTree(text);
            if (node == null) return null;
            return JSON.writeValueAsString(mask(node, 0));
        }
        catch (Exception ex) {
            // Pas de copie de texte libre : impossible de masquer ses champs de façon fiable.
            return null;
        }
    }

    private static JsonNode mask(JsonNode node, int depth) {
        if (depth > 12) return TextNode.valueOf("[Profondeur limitée]");
        if (node.isObject()) {
            var object = JSON.createObjectNode();
            node.fields().forEachRemaining(entry -> object.set(entry.getKey(),
                    SECRET.matcher(entry.getKey()).find() ? TextNode.valueOf("[Masqué]")
                            : mask(entry.getValue(), depth + 1)));
            return object;
        }
        if (node.isArray()) {
            var array = JSON.createArrayNode();
            node.forEach(value -> array.add(mask(value, depth + 1)));
            return array;
        }
        if (node.isTextual()) return TextNode.valueOf(node.textValue()
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [Masqué]"));
        return node;
    }
}
