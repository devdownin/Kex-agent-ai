// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

/**
 * Un modèle offert par la passerelle.
 *
 * @param toolCalling   {@code null} quand la passerelle ne le dit pas. Trois valeurs et non deux :
 *                      cet agent ne peut rien faire sans appel d'outils, et afficher « non » sur
 *                      une passerelle qui n'annonce simplement pas ses capacités écarterait des
 *                      modèles parfaitement utilisables
 * @param contextLength {@code null} pour la même raison
 * @param selected      celui que {@code OPENROUTER_MODEL} désigne aujourd'hui
 */
public record LlmModel(String id, String name, Integer contextLength, Boolean toolCalling,
                       boolean selected) {
}
