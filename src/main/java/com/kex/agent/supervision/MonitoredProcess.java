// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

/**
 * Un processus d'intégration à surveiller. Volontairement sans vocabulaire Kafka : le domaine
 * décrit ce qu'on surveille, les outils MCP décrivent comment on l'observe. {@code hint} est
 * transmis au modèle pour qu'il sache quels outils interroger pour ce processus précis.
 *
 * @param thresholds seuils propres à ce processus, {@code null} par défaut pour hériter des seuils
 *                   globaux de la politique en vigueur — voir {@link Thresholds#withOverrides}
 */
public record MonitoredProcess(String id, String name, String description, String hint,
                               ThresholdOverrides thresholds) {
}
