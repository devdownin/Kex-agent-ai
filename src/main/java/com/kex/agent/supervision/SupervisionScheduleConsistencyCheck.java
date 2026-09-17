// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * {@code kex.agent.supervision.schedule.enabled=true} hors du profil {@code shared-memory} ne
 * démarre aucun cycle automatique — {@link SupervisionConfig.SupervisionScheduleConfig} n'existe
 * que sous ce profil, seul endroit où le verrou multi-instance de {@link SupervisionScheduler}
 * peut s'appuyer sur un {@code JdbcTemplate}. Sans cet avertissement, la propriété se lit comme
 * activée alors qu'elle ne produit rien, un silence indiscernable d'un cycle qui n'a simplement
 * pas encore eu l'occasion de se déclencher.
 *
 * <p>Un avertissement, pas un échec : même posture que {@link
 * com.kex.agent.config.LlmProviderCheck}, l'agent doit démarrer pour qu'on puisse aller lire
 * pourquoi rien ne se déclenche.
 */
@Component
class SupervisionScheduleConsistencyCheck {

    private static final Logger log = LoggerFactory.getLogger(SupervisionScheduleConsistencyCheck.class);

    private final SupervisionProperties properties;
    private final Environment environment;

    SupervisionScheduleConsistencyCheck(SupervisionProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    void check() {
        if (properties.schedule().enabled() && !environment.acceptsProfiles(Profiles.of("shared-memory"))) {
            log.warn("kex.agent.supervision.schedule.enabled=true sans le profil 'shared-memory' actif : "
                    + "aucun cycle ne part seul, le verrou multi-instance qui protège le départ automatique "
                    + "n'existe que sous ce profil");
        }
    }
}
