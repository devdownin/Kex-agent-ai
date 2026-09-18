// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Duration;

import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Mémoire long-terme, distincte de la fenêtre de conversation ({@code max-history-messages}) : ce
 * que le modèle choisit explicitement de retenir au-delà d'un échange, jamais une capture
 * automatique. La panne la plus citée sur ce sujet est l'absence de garde d'écriture — tout devient
 * permanent, la relecture finit par ne renvoyer que du bruit. Le modèle décide quoi écrire ; ces
 * plafonds décident combien en rester.
 */
@ConfigurationProperties("kex.agent.memory")
@Validated
public record MemoryProperties(

        @DefaultValue("true") boolean enabled,

        /** Souvenirs actifs conservés au-delà de cette limite, le plus ancien évincé en premier. */
        @DefaultValue("200") @Positive int capacity,

        /** Un souvenir plus long est tronqué : l'outil retient un fait, pas un paragraphe. */
        @DefaultValue("500") @Positive int maxContentLength,

        /** Un souvenir non réécrit depuis cette durée n'est plus relu, sans être supprimé. */
        @DefaultValue("30d") @DurationMin(millis = 1) Duration retention) {
}
