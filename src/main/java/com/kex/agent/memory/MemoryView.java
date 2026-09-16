// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Instant;

/**
 * Ce que le Control Center affiche : plus que ce que {@code recall_facts} rend au modèle
 * ({@link MemoryFact}), parce qu'un opérateur qui décide de supprimer un souvenir doit voir d'où il
 * vient et depuis quand, pas seulement son contenu.
 */
public record MemoryView(String id, String content, String conversationId, Instant createdAt) {
}
