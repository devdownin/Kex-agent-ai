// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Instant;

/**
 * Entrée d'audit immuable. Elle porte de quoi reconstituer une situation sans l'état courant :
 * l'acteur, la raison, la politique en vigueur et le résultat.
 *
 * @param correlationId relie l'entrée à la décision, au cycle et à l'anomalie dont elle découle —
 *                       un identifiant de domaine, indépendant de toute infrastructure
 * @param traceId        identifiant de la trace de distribution en cours au moment de l'écriture,
 *                       {@code null} hors d'une trace active (échantillonnage à 0, ou cycle
 *                       déclenché hors d'une requête tracée). Relie l'audit à ce qu'une trace
 *                       OpenTelemetry montre du même échange, sans se substituer à
 *                       {@code correlationId} : deux identités différentes, deux usages différents.
 */
public record AuditEntry(String id, Instant at, String actor, String action, String processId,
                         String decisionId, String reason, String policyVersion, String result,
                         String correlationId, String traceId) {
}
