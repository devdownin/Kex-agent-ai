// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Instant;

/**
 * Un souvenir écrit par le modèle, jamais mis à jour : une correction s'écrit comme un nouveau
 * souvenir plutôt que de réécrire l'ancien, qui vieillit et sort de la fenêtre de rétention comme
 * n'importe quel autre.
 */
record MemoryEntry(String id, String content, String conversationId, Instant createdAt) {
}
