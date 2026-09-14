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
 * Enveloppe les outils pour mesurer chaque appel. Les observations de Spring AI donnent déjà des
 * métriques agrégées ; ce qui manque est le détail d'un échange précis, pour le rendre à l'appelant
 * — sans quoi le flux reste muet pendant qu'un outil tourne, jusqu'à une minute.
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
            return delegate.call(toolInput);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            ToolCallRecorder recorder = toolContext == null ? null
                    : ToolCallRecorder.from(toolContext.getContext().get(ToolCallRecorder.CONTEXT_KEY));
            if (recorder == null) {
                return delegate.call(toolInput, toolContext);
            }
            long start = System.nanoTime();
            boolean failed = true;
            try {
                String result = delegate.call(toolInput, toolContext);
                failed = false;
                return result;
            }
            finally {
                recorder.record(getToolDefinition().name(),
                        (System.nanoTime() - start) / 1_000_000, failed);
            }
        }
    }
}
