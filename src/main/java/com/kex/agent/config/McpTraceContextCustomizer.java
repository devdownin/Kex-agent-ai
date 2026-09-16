// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.net.URI;
import java.net.http.HttpRequest;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;

/**
 * Propage le contexte de trace courant (W3C {@code traceparent} par défaut) sur les appels HTTP
 * vers les serveurs MCP. Sans cet en-tête, un serveur MCP lui-même instrumenté ouvrirait une trace
 * détachée de celle qui a déclenché l'appel, et corréler une requête bout en bout redeviendrait un
 * rapprochement manuel d'horodatages entre deux journaux.
 */
class McpTraceContextCustomizer implements McpSyncHttpClientRequestCustomizer {

    private final Tracer tracer;
    private final Propagator propagator;

    McpTraceContextCustomizer(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public void customize(HttpRequest.Builder builder, String method, URI uri, String body,
                          McpTransportContext context) {
        Span current = tracer.currentSpan();
        if (current == null) {
            // Introspection en arrière-plan (initializePending) ou appel hors d'une requête tracée :
            // rien à propager, et propagator.inject sur un contexte absent lèverait plutôt que de
            // simplement ne rien poser.
            return;
        }
        propagator.inject(current.context(), builder, HttpRequest.Builder::header);
    }
}
