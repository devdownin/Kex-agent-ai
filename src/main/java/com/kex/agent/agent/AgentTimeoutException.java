// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.time.Duration;

public class AgentTimeoutException extends RuntimeException {

    private final String conversationId;

    public AgentTimeoutException(Duration timeout, String conversationId) {
        super("Pas de réponse complète en %ds".formatted(timeout.toSeconds()));
        this.conversationId = conversationId;
    }

    /**
     * L'échange continue en arrière-plan : l'appel bloquant de Spring AI n'est pas interruptible,
     * et sa réponse sera écrite dans cette conversation après que l'appelant a reçu son échec. Sans
     * cet identifiant, celui qui n'en avait pas fourni ne saurait même pas où elle atterrit.
     */
    public String conversationId() {
        return conversationId;
    }
}
