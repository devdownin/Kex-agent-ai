// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

/** {@code awardedPoints} vaut {@code criterion.weight()} si {@code MET}, {@code 0} sinon. */
public record McpTrustCriterionResult(McpTrustCriterion criterion, McpTrustCriterionState state, int awardedPoints,
                                      String reason) {
    public McpTrustCriterionResult {
        int expected = state == McpTrustCriterionState.MET ? criterion.weight() : 0;
        if (awardedPoints != expected) {
            throw new IllegalArgumentException(
                    "awardedPoints (" + awardedPoints + ") ne correspond pas à l'état " + state
                            + " pour " + criterion + " (attendu " + expected + ")");
        }
    }

    static McpTrustCriterionResult met(McpTrustCriterion criterion, String reason) {
        return new McpTrustCriterionResult(criterion, McpTrustCriterionState.MET, criterion.weight(), reason);
    }

    static McpTrustCriterionResult notMet(McpTrustCriterion criterion, String reason) {
        return new McpTrustCriterionResult(criterion, McpTrustCriterionState.NOT_MET, 0, reason);
    }

    static McpTrustCriterionResult unknown(McpTrustCriterion criterion, String reason) {
        return new McpTrustCriterionResult(criterion, McpTrustCriterionState.UNKNOWN, 0, reason);
    }
}
