// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.util.Map;

import com.kex.agent.supervision.SupervisionService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seul l'audit et la mémoire de conversation étaient couverts pour {@code shared-memory} ; la
 * mémoire long-terme du modèle suit la même règle et mérite la même vérification bout en bout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:kex-memory;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.chat.memory.repository.jdbc.platform=h2"
})
@ActiveProfiles({"test", "shared-memory"})
class SharedMemoryRepositoryTest {

    @Autowired
    MemoryRepository memoryRepository;

    @Autowired
    MemoryTools memoryTools;

    @Autowired
    MemoryService memoryService;

    @Autowired
    MemoryController memoryController;

    @Autowired
    SupervisionService supervision;

    @Test
    void utilise_le_depot_jdbc() {
        assertThat(memoryRepository).isInstanceOf(JdbcMemoryRepository.class);
    }

    @Test
    void persiste_et_relit_un_souvenir() {
        ToolContext context = new ToolContext(Map.of());

        memoryTools.rememberFact("fait persistant", null, context);

        assertThat(memoryTools.recallFacts(context)).extracting(MemoryFact::content)
                .contains("fait persistant");
    }

    @Test
    void une_suppression_par_un_operateur_rejoint_l_audit_de_supervision() {
        memoryService.remember("opérateur", "fait à effacer via JDBC", null, "conv-1");
        String id = memoryService.list("opérateur").stream()
                .filter(view -> view.content().equals("fait à effacer via JDBC"))
                .findFirst().orElseThrow().id();

        memoryController.forget(id, () -> "opérateur");

        assertThat(memoryService.list()).extracting(MemoryView::id).doesNotContain(id);
        assertThat(supervision.audit()).anySatisfy(entry -> {
            assertThat(entry.actor()).isEqualTo("opérateur");
            assertThat(entry.result()).isEqualTo("fait à effacer via JDBC");
        });
    }
}
