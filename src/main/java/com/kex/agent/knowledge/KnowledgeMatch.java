// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.knowledge;

import java.util.Map;

public record KnowledgeMatch(String id, String text, Map<String, Object> metadata, Double score) {
}
