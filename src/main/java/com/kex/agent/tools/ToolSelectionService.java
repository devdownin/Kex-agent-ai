// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.tools;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.kex.agent.config.AgentToolRegistry;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** La pertinence choisit uniquement parmi les outils déjà autorisés, jamais une permission. */
@Component
public class ToolSelectionService {

    private final ToolControlProperties properties;
    private final Supplier<ToolCallback[]> tools;

    @Autowired
    public ToolSelectionService(ToolControlProperties properties, AgentToolRegistry registry) {
        this(properties, registry::callbacks);
    }

    public ToolSelectionService(ToolControlProperties properties, Supplier<ToolCallback[]> tools) {
        this.properties = properties;
        this.tools = tools;
    }

    public List<ToolCallback> select(String request, Set<String> allowedTools) {
        // Une liste vide signifie aucun outil. Ne pas découvrir les serveurs pour ce cas.
        if (allowedTools != null && allowedTools.isEmpty()) return List.of();
        List<ToolCallback> authorized = Arrays.stream(tools.get())
                .filter(tool -> allowedTools == null || allowedTools.contains(tool.getToolDefinition().name()))
                .toList();
        if (!properties.selectionEnabled()) return authorized;
        Set<String> words = words(request);
        // Les descriptions et schémas MCP sont non fiables : ils ne participent pas au score.
        Comparator<ToolCallback> rank = Comparator
                .comparingLong((ToolCallback tool) -> score(tool.getToolDefinition().name(), words)).reversed()
                .thenComparing(tool -> tool.getToolDefinition().name());
        return authorized.stream().sorted(rank).limit(properties.maxSelectedTools()).toList();
    }

    private static long score(String name, Set<String> request) {
        return words(name).stream().filter(request::contains).count();
    }

    private static Set<String> words(String text) {
        if (text == null) return Set.of();
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(word -> word.length() > 1).collect(Collectors.toSet());
    }
}
