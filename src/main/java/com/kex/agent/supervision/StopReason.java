// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

/**
 * Pourquoi un relevé s'est arrêté. Repris à l'identique du contrat {@code Coverage} de Kafka SQL
 * Explorer, pour que le vocabulaire du serveur MCP et celui du tableau de bord soient le même.
 */
public enum StopReason {

    /** Tout ce qui devait être lu l'a été : le relevé est complet. */
    EXHAUSTED,

    TIME_BUDGET,
    TOPIC_LIMIT,
    RECORD_LIMIT,
    CANCELLED,
    PARTIAL_FAILURE,

    /**
     * Aucune enveloppe de couverture n'est remontée. Ce n'est pas une affirmation de complétude :
     * la plupart des serveurs MCP n'en portent pas, et le modèle peut aussi l'avoir omise. On ne
     * peut pas distinguer les deux, donc on ne conclut ni dans un sens ni dans l'autre — on le dit.
     */
    NOT_REPORTED
}
