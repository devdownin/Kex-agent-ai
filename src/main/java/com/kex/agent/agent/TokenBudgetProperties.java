// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.util.Map;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Costs are integer millionths of the deployment's configured currency. Zero disables a limit. */
@ConfigurationProperties("kex.agent.token-budget")
@Validated
public record TokenBudgetProperties(@DefaultValue("0") @PositiveOrZero long dailyLimit,
        @DefaultValue("0") @PositiveOrZero long tenantDailyLimit,
        @DefaultValue("0") @PositiveOrZero long taskLimit,
        @DefaultValue("0") @PositiveOrZero long dailyCostMicros,
        @DefaultValue("0") @PositiveOrZero long tenantDailyCostMicros,
        @DefaultValue("0") @PositiveOrZero long taskCostMicros,
        @DefaultValue("4096") @Positive int outputReservation,
        @DefaultValue("0") @PositiveOrZero long inputTokenCostMicros,
        @DefaultValue("0") @PositiveOrZero long outputTokenCostMicros,
        @DefaultValue("0") @PositiveOrZero long defaultToolCostMicros,
        Map<String, Long> toolCostMicros,
        @DefaultValue(".kex/budgets.json") String storePath) {
    public TokenBudgetProperties {
        toolCostMicros = toolCostMicros == null ? Map.of() : Map.copyOf(toolCostMicros);
        if (toolCostMicros.values().stream().anyMatch(v -> v == null || v < 0)) {
            throw new IllegalArgumentException("Tarifs d'outils négatifs interdits");
        }
    }
    public TokenBudgetProperties(long dailyLimit) {
        this(dailyLimit, 0, 0, 0, 0, 0, 4096, 0, 0, 0, Map.of(), "");
    }
}
