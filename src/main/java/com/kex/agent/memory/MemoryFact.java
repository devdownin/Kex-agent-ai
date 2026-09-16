// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

/**
 * Ce que {@code recall_facts} rend au modèle. L'identifiant en fait partie : sans lui, le modèle ne
 * peut désigner le souvenir qu'il corrige, et une correction s'ajouterait à côté du fait devenu
 * faux au lieu de le remplacer.
 */
public record MemoryFact(String id, String content) {
}
