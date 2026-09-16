// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limite les routes qui appellent le modèle, par principal authentifié : avec le seul bearer
 * historique il n'existe qu'un principal, donc un budget d'instance comme avant ; avec
 * {@code kex.agent.api-keys}, chaque nom a le sien, pour qu'une clé qui tourne en boucle n'affame
 * pas les autres. En multi-instance, chaque réplique a son propre jeu de seaux — diviser le seuil
 * en conséquence.
 */
@ConfigurationProperties("kex.agent.rate-limit")
public record RateLimitProperties(

        @DefaultValue("true") boolean enabled,

        /** Débit soutenu autorisé. */
        @DefaultValue("60") int requestsPerMinute,

        /** Pointe tolérée au-delà du débit soutenu. */
        @DefaultValue("20") int burst) {
}
