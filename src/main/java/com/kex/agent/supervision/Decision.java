// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Instant;
import java.util.List;

/**
 * Trace d'une décision, identifiée pour l'audit et le support. {@code correlationId} relie la
 * décision au cycle, à l'anomalie et à l'entrée d'audit qui en découlent.
 *
 * @param estimatedImpact impact estimé de l'action, affiché avant toute validation humaine
 * @param policyVersion   version de politique en vigueur au moment de la décision : sans elle, une
 *                        décision relue six mois plus tard est inexplicable
 */
public record Decision(String id, String cycleId, String anomalyId, String processId, String processName,
                       Capability capability, String objective, String context, String action,
                       List<Observation> observations, String estimatedImpact, double confidence,
                       DecisionStatus status, String result, String policyVersion, String correlationId,
                       Instant decidedAt, Instant resolvedAt, Instant expiresAt) {

    public Decision resolvedAs(DecisionStatus newStatus, String outcome, Instant at) {
        return new Decision(id, cycleId, anomalyId, processId, processName, capability, objective, context,
                action, observations, estimatedImpact, confidence, newStatus, outcome, policyVersion,
                correlationId, decidedAt, at, expiresAt);
    }
}
