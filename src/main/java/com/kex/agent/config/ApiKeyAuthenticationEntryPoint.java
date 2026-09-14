// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.util.StringUtils;

/**
 * Distingue « jeton absent ou faux » de « service non configuré » : un 401 sur une installation
 * sans clé laisserait croire à une erreur d'appelant alors que rien ne peut réussir.
 */
class ApiKeyAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final boolean configured;

    ApiKeyAuthenticationEntryPoint(String apiKey) {
        this.configured = StringUtils.hasText(apiKey);
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        if (configured) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return;
        }
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"detail\":\"kex.agent.api-key n'est pas configuré\"}");
    }
}
