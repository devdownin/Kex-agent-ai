// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce que le profil {@code shared-memory} bascule réellement sur la base, et ce qu'il n'y bascule
 * pas. Trois pièces le font : l'audit, parce qu'une pièce de conformité partielle derrière un
 * load balancer ne vaut rien ; l'état décisionnel — décisions, pause, fenêtres de maintenance —
 * parce que sa divergence n'est pas un écran discordant mais une action fausse ; et le seau à
 * jetons, sans quoi trois répliques accordent trois fois le seuil annoncé — celui-là est vérifié
 * dans son propre paquet, par {@code SharedRateLimiterTest}.
 *
 * <p>Cycles, anomalies brutes et relevés de processus restent en mémoire du processus, documenté
 * et assumé dans ARCHITECTURE.md.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:kex-audit;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.chat.memory.repository.jdbc.platform=h2"
})
@ActiveProfiles({"test", "shared-memory"})
class SharedSupervisionAuditTest {

    @Autowired
    AuditRepository auditRepository;

    @Autowired
    SupervisionService supervision;

    @Autowired
    SupervisionStateRepository state;

    @Test
    void utilise_le_depot_jdbc() {
        assertThat(auditRepository).isInstanceOf(JdbcAuditRepository.class);
        assertThat(state).isInstanceOf(JdbcSupervisionStateRepository.class);
    }

    /**
     * La pause traverse la base : c'est l'autre réplique qui doit la voir, pas seulement le champ
     * de celle qui l'a posée.
     */
    @Test
    void la_pause_passe_par_l_etat_partage() {
        supervision.pause("opérateur");

        assertThat(state.paused()).isTrue();
        assertThat(supervision.status().paused()).isTrue();

        supervision.resume("opérateur");

        assertThat(state.paused()).isFalse();
    }

    @Test
    void persiste_et_relit_une_entree_d_audit() {
        supervision.pause("opérateur");

        assertThat(supervision.audit()).anySatisfy(entry -> assertThat(entry.actor()).isEqualTo("opérateur"));
    }
}
