// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import reactor.core.publisher.Flux;

/**
 * L'identifiant est rendu <b>avec</b> le flux, pas seulement écrit en mémoire : sans lui le client
 * qui n'en a pas fourni ne peut ni enchaîner un second message ni purger la conversation créée.
 */
public record AgentStream(String conversationId, Flux<AgentEvent> events) {
}
