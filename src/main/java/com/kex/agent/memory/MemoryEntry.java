// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Instant;

/**
 * Un souvenir écrit par le modèle, jamais modifié dans son contenu : une correction s'écrit comme
 * un nouveau souvenir qui marque l'ancien périmé.
 *
 * @param supersededBy identifiant du souvenir qui remplace celui-ci, {@code null} tant qu'il reste
 *                     valable. Marqué plutôt que supprimé : savoir qu'un fait a été corrigé, et par
 *                     quoi, vaut mieux que de le voir disparaître sans trace.
 */
record MemoryEntry(String id, String content, String conversationId, Instant createdAt,
                   String supersededBy) {
}
