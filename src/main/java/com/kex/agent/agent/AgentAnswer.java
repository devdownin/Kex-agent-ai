// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.util.List;

/**
 * @param tools        les outils réellement exécutés pour produire cette réponse, dans l'ordre
 * @param usage        les jetons consommés, {@code null} si le fournisseur n'en rend pas
 * @param finishReason pourquoi le modèle s'est arrêté, dans le vocabulaire du fournisseur
 *                     ({@code max_tokens} chez Anthropic, {@code length} chez OpenAI) : rendu tel
 *                     quel plutôt que normalisé, un mot inconnu ne devant pas devenir un verdict.
 *                     Sans lui, une réponse coupée au plafond de jetons se lit exactement comme une
 *                     réponse complète.
 */
public record AgentAnswer(String conversationId, String content, List<AgentEvent.ToolCall> tools,
                          AgentUsage usage, String finishReason) {
}
