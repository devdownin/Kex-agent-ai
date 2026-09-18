// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Plafond de jetons journalier, toutes conversations confondues. {@code 0} lève la borne : une
 * installation existante ne voit rien changer tant que personne n'a choisi un chiffre.
 *
 * <p>Le compteur est tenu en mémoire, par instance — comme {@code RateLimitProperties} : en
 * multi-instance, chaque réplique a son propre budget, à diviser le seuil en conséquence.
 */
@ConfigurationProperties("kex.agent.token-budget")
@Validated
public record TokenBudgetProperties(@DefaultValue("0") @PositiveOrZero long dailyLimit) {
}
