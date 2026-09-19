// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.util.Arrays;
import java.util.function.Supplier;

import com.kex.agent.agent.AgentExecution;
import com.kex.agent.agent.TaskToolPolicy;
import com.kex.agent.agent.ToolCallRecorder;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Enveloppe les outils pour mesurer chaque appel, les protéger par le même disjoncteur que
 * {@link com.kex.agent.mcp.McpToolCatalog}, et baliser leur résultat comme une donnée non fiable.
 * Les observations de Spring AI donnent déjà des métriques agrégées ; ce qui manque est le détail
 * d'un échange précis, pour le rendre à l'appelant — sans quoi le flux reste muet pendant qu'un
 * outil tourne, jusqu'à une minute.
 *
 * <p>Ces {@link ToolCallback} viennent de l'autoconfiguration MCP de Spring AI et appellent le
 * {@code McpSyncClient} directement, sans passer par {@code McpToolCatalog.call} : sans le même
 * disjoncteur ici, le chemin que le modèle emprunte à chaque conversation resterait le seul à
 * attendre le plafond de temps complet à chaque appel pendant qu'un serveur MCP dégrade, là où
 * l'invocation directe échouerait déjà vite.
 *
 * <p>Le balisage {@code <tool_result>} défend contre l'injection indirecte : un serveur MCP peut
 * renvoyer n'importe quel texte — un nom de topic, un message applicatif — et rien ne garantit
 * qu'il ne contienne pas une phrase qui ressemble à une instruction. Le prompt système dit au
 * modèle de lire ce qui est entre ces balises comme une donnée, jamais comme une consigne ; ceci
 * pose la balise, quel que soit le serveur MCP branché.
 */
class RecordingToolCallbackProvider implements ToolCallbackProvider {

    private final ToolCallbackProvider delegate;
    private final CircuitBreaker circuitBreaker;

    RecordingToolCallbackProvider(ToolCallbackProvider delegate, CircuitBreaker circuitBreaker) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return Arrays.stream(delegate.getToolCallbacks())
                .map(callback -> new RecordingToolCallback(callback, circuitBreaker))
                .toArray(ToolCallback[]::new);
    }

    private record RecordingToolCallback(ToolCallback delegate, CircuitBreaker circuitBreaker)
            implements ToolCallback {

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
            return wrapUntrusted(getToolDefinition().name(), protect(() -> delegate.call(toolInput)));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            String name = getToolDefinition().name();
            return wrapUntrusted(name, ToolCallRecorder.timed(toolContext, name,
                    () -> {
                        AgentExecution.ensureActive(toolContext);
                        TaskToolPolicy.check(name, toolContext);
                        return protect(() -> delegate.call(toolInput, toolContext));
                    }));
        }

        private String protect(Supplier<String> call) {
            return CircuitBreaker.decorateSupplier(circuitBreaker, call).get();
        }

        /**
         * Une erreur d'outil (isError côté MCP) reste du texte fourni par le serveur, donc tout
         * aussi peu fiable que le résultat réussi : elle passe par le même balisage plutôt que
         * d'en être exemptée.
         */
        private static String wrapUntrusted(String toolName, String content) {
            return "<tool_result tool=\"%s\" trust=\"untrusted\">\n%s\n</tool_result>"
                    .formatted(xml(toolName), xml(content));
        }

        /** Empêche une donnée externe de fermer la balise de confiance ou d'en injecter une autre. */
        private static String xml(String value) {
            if (value == null) {
                return "";
            }
            return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;");
        }
    }
}
