// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

public record ChatRequest(String conversationId,
                          @NotBlank @Size(max = 32_000) String message,
                          @Pattern(regexp = "(?i)CHAT|TRIAGE|DIAGNOSTIC") String task) {
    public ChatRequest(String conversationId, String message) {
        this(conversationId, message, null);
    }
}
