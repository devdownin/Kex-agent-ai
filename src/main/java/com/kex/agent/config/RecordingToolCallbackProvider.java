// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.util.Arrays;

import com.kex.agent.agent.ToolCallRecorder;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Enveloppe les outils pour mesurer chaque appel et baliser leur résultat comme une donnée non
 * fiable. Les observations de Spring AI donnent déjà des métriques agrégées ; ce qui manque est le
 * détail d'un échange précis, pour le rendre à l'appelant — sans quoi le flux reste muet pendant
 * qu'un outil tourne, jusqu'à une minute.
 *
 * <p>Le balisage {@code <tool_result>} défend contre l'injection indirecte : un serveur MCP peut
 * renvoyer n'importe quel texte — un nom de topic, un message applicatif — et rien ne garantit
 * qu'il ne contienne pas une phrase qui ressemble à une instruction. Le prompt système dit au
 * modèle de lire ce qui est entre ces balises comme une donnée, jamais comme une consigne ; ceci
 * pose la balise, quel que soit le serveur MCP branché.
 */
class RecordingToolCallbackProvider implements ToolCallbackProvider {

    private final ToolCallbackProvider delegate;

    RecordingToolCallbackProvider(ToolCallbackProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return Arrays.stream(delegate.getToolCallbacks())
                .map(RecordingToolCallback::new)
                .toArray(ToolCallback[]::new);
    }

    private record RecordingToolCallback(ToolCallback delegate) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return wrapUntrusted(getToolDefinition().name(), delegate.call(toolInput));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            String name = getToolDefinition().name();
            return wrapUntrusted(name,
                    ToolCallRecorder.timed(toolContext, name, () -> delegate.call(toolInput, toolContext)));
        }

        /**
         * Une erreur d'outil (isError côté MCP) reste du texte fourni par le serveur, donc tout
         * aussi peu fiable que le résultat réussi : elle passe par le même balisage plutôt que
         * d'en être exemptée.
         */
        private static String wrapUntrusted(String toolName, String content) {
            return "<tool_result tool=\"%s\" trust=\"untrusted\">\n%s\n</tool_result>"
                    .formatted(toolName, content);
        }
    }
}
