// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.util.Map;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Outil MCP lié à une capacité. Sans liaison, la capacité reste recommandable mais inexécutable —
 * et c'est l'état par défaut : un agent n'hérite pas du droit d'agir d'une installation.
 *
 * @param arguments arguments fixes ; {@code processId} et {@code decisionId} y sont ajoutés à
 *                  l'appel pour que l'outil sache sur quoi il agit
 */
public record ActionBinding(String connection, String tool, @DefaultValue Map<String, Object> arguments) {
}
