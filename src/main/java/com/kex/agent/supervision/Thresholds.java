// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Duration;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.PositiveOrZero;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Seuils de détection. Ils ne déclenchent rien seuls : ils sont transmis au modèle comme critères
 * explicites, pour qu'une anomalie soit justifiable par un nombre plutôt que par une impression.
 */
public record Thresholds(

        @DefaultValue("1000") @PositiveOrZero long consumerLag,

        /** Taux d'erreur en pourcentage, pas en fraction : c'est l'unité affichée à l'écran. */
        @DefaultValue("2.0") @DecimalMin("0.0") @DecimalMax("100.0") double errorRatePercent,

        @DefaultValue("5m") @DurationMin(millis = 1) Duration processingTime,

        @DefaultValue("50") @PositiveOrZero long blockedMessages,

        /** Fenêtre sur laquelle une tendance est jugée : en deçà, un pic isolé passe pour une dérive. */
        @DefaultValue("15m") @DurationMin(millis = 1) Duration observationWindow) {

    /**
     * Un processus à faible trafic et un à fort débit partagent sinon les mêmes seuils, trop
     * sensibles pour l'un ou trop laxistes pour l'autre. Chaque champ de {@code override} remplace
     * le sien ; un champ omis (donc {@code null}) laisse le seuil global tel quel.
     */
    Thresholds withOverrides(ThresholdOverrides override) {
        if (override == null) {
            return this;
        }
        return new Thresholds(
                override.consumerLag() != null ? override.consumerLag() : consumerLag(),
                override.errorRatePercent() != null ? override.errorRatePercent() : errorRatePercent(),
                override.processingTime() != null ? override.processingTime() : processingTime(),
                override.blockedMessages() != null ? override.blockedMessages() : blockedMessages(),
                override.observationWindow() != null ? override.observationWindow() : observationWindow());
    }
}
