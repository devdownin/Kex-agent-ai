// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import reactor.core.publisher.Sinks;

/**
 * Collecte les outils exécutés pendant un échange. Transporté par le {@code ToolContext} de la
 * requête plutôt que par un ThreadLocal : la boucle d'outils du chemin en flux s'exécute sur des
 * threads Reactor, où un ThreadLocal ne suit pas.
 */
public final class ToolCallRecorder {

    /** Clé dans le ToolContext. Le préfixe {@code kex.} est filtré avant l'envoi au serveur MCP. */
    public static final String CONTEXT_KEY = "kex.tool-recorder";

    private final List<AgentEvent.ToolCall> calls = new CopyOnWriteArrayList<>();
    private final Sinks.Many<AgentEvent> sink;

    public ToolCallRecorder(Sinks.Many<AgentEvent> sink) {
        this.sink = sink;
    }

    public ToolCallRecorder() {
        this(null);
    }

    public void record(String tool, long durationMillis, boolean failed) {
        AgentEvent.ToolCall call = new AgentEvent.ToolCall(tool, durationMillis, failed);
        calls.add(call);
        if (sink != null) {
            sink.tryEmitNext(call);
        }
    }

    public List<AgentEvent.ToolCall> calls() {
        return List.copyOf(calls);
    }

    public static ToolCallRecorder from(Object candidate) {
        return candidate instanceof ToolCallRecorder recorder ? recorder : null;
    }

    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(System.identityHashCode(this));
    }
}
