// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

/**
 * {@code UNKNOWN} n'est pas {@code NOT_MET} : une source qui n'expose pas le signal d'un critère
 * (ex. aucun registre consulté ici ne porte d'attestation de signature) ne prouve pas son absence,
 * seulement que rien ne l'a mesuré — même distinction que {@code Coverage} pour les relevés Kafka.
 * Les deux ne comptent aucun point ; seule la raison affichée diffère.
 */
public enum McpTrustCriterionState {
    MET, NOT_MET, UNKNOWN
}
