// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seul l'audit bascule sur Postgres avec le profil {@code shared-memory} : c'est la pièce de
 * conformité qui ne doit jamais être partielle derrière un load balancer. Cycles, anomalies et
 * décisions restent en mémoire du processus, documenté et assumé dans ARCHITECTURE.md.
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

    @Test
    void utilise_le_depot_jdbc() {
        assertThat(auditRepository).isInstanceOf(JdbcAuditRepository.class);
    }

    @Test
    void persiste_et_relit_une_entree_d_audit() {
        supervision.pause("opérateur");

        assertThat(supervision.audit()).anySatisfy(entry -> assertThat(entry.actor()).isEqualTo("opérateur"));
    }
}
