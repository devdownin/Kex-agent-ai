// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limite les routes qui appellent le modèle. Le bearer est unique et partagé, donc la limite est
 * globale à l'instance : elle protège le budget, elle ne répartit pas l'accès entre appelants.
 * En multi-instance, chaque réplique a son propre seau — diviser le seuil en conséquence.
 */
@ConfigurationProperties("kex.agent.rate-limit")
public record RateLimitProperties(

        @DefaultValue("true") boolean enabled,

        /** Débit soutenu autorisé. */
        @DefaultValue("60") int requestsPerMinute,

        /** Pointe tolérée au-delà du débit soutenu. */
        @DefaultValue("20") int burst) {
}
