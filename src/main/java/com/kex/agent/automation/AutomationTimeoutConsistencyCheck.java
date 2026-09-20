// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.automation;

import com.kex.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * {@code AutomationService.tick()} borne la réclamation d'une tâche par {@code
 * kex.agent.automation.timeout} ({@link JdbcAutomationRepository#claim}), mais l'exécution
 * elle-même ({@code AgentService.askReadOnly}) est bornée séparément par {@code
 * kex.agent.request-timeout}. Si le bail expire avant la fin réelle du tour du modèle, une autre
 * réplique peut réclamer et exécuter la même automatisation en double — précisément le
 * multi-instance visé par le profil {@code shared-memory} sous lequel ce module tourne.
 *
 * <p>Un avertissement, pas un échec : même posture que {@link
 * com.kex.agent.supervision.SupervisionScheduleConsistencyCheck}.
 */
@Component
class AutomationTimeoutConsistencyCheck {

    private static final Logger log = LoggerFactory.getLogger(AutomationTimeoutConsistencyCheck.class);

    private final AutomationProperties automation;
    private final AgentProperties agent;

    AutomationTimeoutConsistencyCheck(AutomationProperties automation, AgentProperties agent) {
        this.automation = automation;
        this.agent = agent;
    }

    @EventListener(ApplicationReadyEvent.class)
    void check() {
        if (automation.enabled() && automation.timeout().compareTo(agent.requestTimeout()) < 0) {
            log.warn("kex.agent.automation.timeout ({}) est inférieur à kex.agent.request-timeout ({}) : "
                    + "le bail d'une tâche peut expirer avant la fin réelle du tour du modèle, et une autre "
                    + "réplique peut alors réclamer et exécuter la même automatisation en double",
                    automation.timeout(), agent.requestTimeout());
        }
    }
}
