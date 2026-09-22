// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Plafonds partagés par deux systèmes distincts, tous deux distincts de la fenêtre de conversation
 * ({@code max-history-messages}) : {@link MemoryService} (faits que le modèle choisit explicitement
 * de retenir — {@code remember_fact}/{@code recall_facts}) et {@link LongTermMemoryService} (résumés
 * et compétences écrits automatiquement après chaque échange réussi, sans choix du modèle). Voir
 * {@code docs/ARCHITECTURE.md} (« Un second système partage le nom… ») pour ce que {@code capacity}
 * et {@code retention} couvrent exactement dans l'un et dans l'autre.
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
        @DefaultValue("30d") @DurationMin(millis = 1) Duration retention,

        @DefaultValue @Valid Skills skills) {

    /**
     * Ce que la bibliothèque de compétences approuvées pèse réellement dans le prompt. Elle ne fait
     * que grandir — chaque échange réussi en propose une — alors que seules les {@code injected}
     * premières agissent. Sans critère de tri, « les cinq premières » dépendait de l'ordre de
     * stockage : la sixième compétence approuvée n'agissait jamais et rien ne le disait.
     *
     * @param injected           compétences réellement injectées, les plus récemment approuvées d'abord
     * @param charactersPerSkill troncature par compétence, avant le plafond global du bloc de contexte
     * @param staleAfter         au-delà, une compétence approuvée est signalée comme candidate à la
     *                           revue : signalée, jamais retirée d'elle-même
     */
    public record Skills(@DefaultValue("5") @Positive int injected,
                         @DefaultValue("3000") @Positive int charactersPerSkill,
                         @DefaultValue("90d") @DurationMin(millis = 1) Duration staleAfter) {
    }
}
