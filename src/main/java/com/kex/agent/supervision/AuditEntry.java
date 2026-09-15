// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Instant;

/**
 * Entrée d'audit immuable. Elle porte de quoi reconstituer une situation sans l'état courant :
 * l'acteur, la raison, la politique en vigueur et le résultat.
 */
public record AuditEntry(String id, Instant at, String actor, String action, String processId,
                         String decisionId, String reason, String policyVersion, String result,
                         String correlationId) {
}
