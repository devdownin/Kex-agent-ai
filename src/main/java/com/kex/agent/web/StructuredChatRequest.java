// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.web;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** @param schema schéma JSON auquel la réponse doit se conformer */
public record StructuredChatRequest(String conversationId,
                                    @NotBlank @Size(max = 32_000) String message,
                                    @NotEmpty Map<String, Object> schema) {
}
