// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le profil `shared-memory` doit réellement remplacer le dépôt en mémoire par le dépôt JDBC :
 * c'est l'annulation de la liste d'exclusions d'application.yml qui est vérifiée ici, sur H2,
 * le schéma étant fourni par Spring AI pour les deux moteurs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:kex;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.chat.memory.repository.jdbc.platform=h2"
})
@ActiveProfiles({"test", "shared-memory"})
class SharedMemoryProfileTest {

    @Autowired
    ChatMemoryRepository repository;

    @Autowired
    ChatMemory chatMemory;

    @Test
    void utilise_le_depot_jdbc() {
        assertThat(repository.getClass().getName()).contains("Jdbc");
    }

    @Test
    void persiste_et_relit_une_conversation() {
        chatMemory.add("conv-1", new UserMessage("bonjour"));

        assertThat(repository.findConversationIds()).contains("conv-1");
        assertThat(chatMemory.get("conv-1")).extracting(message -> message.getText())
                .isEqualTo(List.of("bonjour"));
    }
}
