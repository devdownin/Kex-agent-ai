// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.net.URI;
import java.net.http.HttpRequest;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class McpTraceContextCustomizerTest {

    @Mock
    Tracer tracer;

    @Mock
    Propagator propagator;

    @Mock
    Span span;

    @Mock
    TraceContext traceContext;

    @Test
    void propage_le_contexte_de_trace_courant() {
        given(tracer.currentSpan()).willReturn(span);
        given(span.context()).willReturn(traceContext);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:8080/mcp"));
        new McpTraceContextCustomizer(tracer, propagator).customize(builder, "POST",
                URI.create("http://localhost:8080/mcp"), null, null);

        verify(propagator).inject(eq(traceContext), eq(builder), any());
    }

    @Test
    void ne_pose_rien_hors_d_une_trace_courante() {
        given(tracer.currentSpan()).willReturn(null);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:8080/mcp"));
        new McpTraceContextCustomizer(tracer, propagator).customize(builder, "POST",
                URI.create("http://localhost:8080/mcp"), null, null);

        verifyNoInteractions(propagator);
        assertThat(builder.build().headers().map()).isEmpty();
    }
}
