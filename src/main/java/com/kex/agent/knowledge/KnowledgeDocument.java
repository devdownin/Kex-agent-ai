// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.knowledge;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;

public record KnowledgeDocument(@NotBlank String text, Map<String, Object> metadata) {
}
