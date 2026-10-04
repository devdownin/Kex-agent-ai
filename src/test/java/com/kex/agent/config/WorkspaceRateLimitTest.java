// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WorkspaceRateLimitTest {
    @Test void le_flux_utilisateur_ne_contourne_pas_la_limite_de_chat() throws Exception {
        var filter = new RateLimitFilter(mock(RateLimiter.class));
        var request = new MockHttpServletRequest("POST", "/api/agent/workspace/requests/stream");
        assertThat(filter.shouldNotFilter(request)).isFalse();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { throw new AssertionError("Le modèle ne doit pas être appelé"); });
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/agent/workspace/requests"))).isTrue();
    }
}
