// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Récite au démarrage la configuration du modèle, avertissements compris.
 *
 * <p>Un avertissement, pas un échec : l'agent doit démarrer pour que {@code /actuator/health}, la
 * console et l'introspection MCP restent joignables — c'est précisément là qu'on va regarder
 * pourquoi rien ne répond. Même posture que l'absence de {@code kex.agent.api-key}, qui se
 * signale au démarrage et se refuse à l'appel.
 *
 * <p>La lecture est celle de {@link LlmViewService}, la même que sert la console : deux lectures
 * séparées divergeraient, et un écran qui contredit les logs fait douter des deux.
 */
@Component
class LlmProviderCheck {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderCheck.class);

    private final LlmViewService llm;

    LlmProviderCheck(LlmViewService llm) {
        this.llm = llm;
    }

    @EventListener(ApplicationReadyEvent.class)
    void check() {
        LlmView view = llm.describe();
        log.info("Modèle de conversation : {} via {}{}", view.model(), view.label(),
                view.baseUrl() == null ? "" : " (" + view.baseUrl() + ")");
        view.warnings().forEach(log::warn);
    }
}
