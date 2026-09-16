// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Disjoncteur et réessai partagés par les intégrations externes (serveurs MCP, fournisseur du
 * modèle). Les exceptions qui comptent comme échec ou déclenchent un réessai ne sont pas ici :
 * elles engagent un type Java précis par intégration, pas une chaîne de caractères dans une
 * configuration qui romprait en silence à un renommage.
 */
@ConfigurationProperties("kex.resilience")
public record ResilienceProperties(

        /** Nombre d'appels glissants sur lesquels le taux d'échec est calculé. */
        @DefaultValue("10") int slidingWindowSize,

        /** En deçà, le taux d'échec n'est pas encore significatif : le disjoncteur reste fermé. */
        @DefaultValue("5") int minimumNumberOfCalls,

        /** Au-delà, le disjoncteur s'ouvre et les appels échouent immédiatement. */
        @DefaultValue("50") float failureRateThreshold,

        /** Attente avant de retenter un appel une fois le disjoncteur ouvert. */
        @DefaultValue("30s") Duration waitDurationInOpenState,

        /** Appels d'essai autorisés pour décider si le disjoncteur referme. */
        @DefaultValue("3") int permittedCallsInHalfOpenState,

        /** Tentatives totales pour un appel MCP explicitement injoignable. */
        @DefaultValue("3") int retryMaxAttempts,

        /** Attente entre deux tentatives, doublée à chaque fois. */
        @DefaultValue("500ms") Duration retryWaitDuration) {
}
