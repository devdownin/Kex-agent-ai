// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Retente périodiquement l'initialisation des clients MCP encore muets, plutôt que d'attendre le
 * prochain appel — {@code servers()} et {@code call(...)} le font déjà à chaque accès, ce qui ne
 * détecte un serveur revenu que si quelqu'un regarde entre-temps.
 */
@ConfigurationProperties("kex.mcp.health-check")
@Validated
public record McpHealthCheckProperties(@DefaultValue("true") boolean enabled,
                                       @DefaultValue("1m") @DurationMin(millis = 1) Duration interval) {
}
