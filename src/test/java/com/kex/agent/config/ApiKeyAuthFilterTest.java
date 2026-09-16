// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ApiKeyAuthFilterTest {

    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authentifie_sous_le_nom_du_jeton_nomme_correspondant() throws Exception {
        filter(Map.of("ops-console", "jeton-ops", "ci-pipeline", "jeton-ci")).doFilter(
                request("Bearer jeton-ci"), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("ci-pipeline");
    }

    @Test
    void authentifie_le_jeton_unique_sous_le_nom_historique() throws Exception {
        filter(Map.of("kex-agent-api", "secret")).doFilter(
                request("Bearer secret"), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("kex-agent-api");
    }

    @Test
    void ne_authentifie_pas_un_jeton_inconnu() throws Exception {
        filter(Map.of("ops-console", "jeton-ops")).doFilter(
                request("Bearer autre-chose"), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(any(), any());
    }

    @Test
    void ne_authentifie_rien_sans_en_tete() throws Exception {
        filter(Map.of("ops-console", "jeton-ops")).doFilter(
                new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static ApiKeyAuthFilter filter(Map<String, String> tokens) {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        tokens.forEach((name, token) -> bytes.put(name, token.getBytes(StandardCharsets.UTF_8)));
        return new ApiKeyAuthFilter(bytes);
    }

    private static MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", authorization);
        return request;
    }
}
