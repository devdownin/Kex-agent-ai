// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Duration;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Seuils de détection. Ils ne déclenchent rien seuls : ils sont transmis au modèle comme critères
 * explicites, pour qu'une anomalie soit justifiable par un nombre plutôt que par une impression.
 */
public record Thresholds(

        @DefaultValue("1000") long consumerLag,

        /** Taux d'erreur en pourcentage, pas en fraction : c'est l'unité affichée à l'écran. */
        @DefaultValue("2.0") double errorRatePercent,

        @DefaultValue("5m") Duration processingTime,

        @DefaultValue("50") long blockedMessages,

        /** Fenêtre sur laquelle une tendance est jugée : en deçà, un pic isolé passe pour une dérive. */
        @DefaultValue("15m") Duration observationWindow) {
}
