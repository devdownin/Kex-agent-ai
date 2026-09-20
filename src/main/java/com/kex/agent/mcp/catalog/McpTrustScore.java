// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

import java.util.List;

/**
 * {@code total} et {@code eligible} ne sont jamais reçus de l'appelant : ils sont recalculés à
 * partir de {@code criteria}/{@code disqualifiers} par le constructeur compact, pour qu'aucune
 * incohérence entre les deux ne puisse être construite. Un candidat disqualifié garde son total —
 * la note reste lisible, seule {@code eligible} conditionne l'installation.
 */
public record McpTrustScore(int total, List<McpTrustCriterionResult> criteria, List<String> disqualifiers,
                            boolean eligible) {
    public McpTrustScore {
        criteria = List.copyOf(criteria);
        disqualifiers = List.copyOf(disqualifiers);
        total = criteria.stream().mapToInt(McpTrustCriterionResult::awardedPoints).sum();
        eligible = disqualifiers.isEmpty();
    }

    public static McpTrustScore of(List<McpTrustCriterionResult> criteria, List<String> disqualifiers) {
        return new McpTrustScore(0, criteria, disqualifiers, false);
    }
}
