// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedChatMemoryTest {

    private final RecordingChatMemory delegate = new RecordingChatMemory();
    private final ChatMemory memory = new BoundedChatMemory(delegate, 10);

    @Test
    void coupe_un_message_utilisateur_trop_long_en_le_disant() {
        memory.add("conv-1", new UserMessage("0123456789 et vingt caractères de plus"));

        assertThat(delegate.stored).singleElement().satisfies(message -> {
            assertThat(message).isInstanceOf(UserMessage.class);
            assertThat(message.getText()).startsWith("0123456789")
                    // Une coupe muette se relit comme un contenu complet : elle doit se voir.
                    .contains("caractères coupés de l'historique");
        });
    }

    @Test
    void coupe_aussi_une_reponse_de_l_assistant() {
        memory.add("conv-1", List.of(new AssistantMessage("0123456789 et la suite de la réponse")));

        assertThat(delegate.stored).singleElement().satisfies(message -> {
            assertThat(message).isInstanceOf(AssistantMessage.class);
            assertThat(message.getText()).contains("caractères coupés de l'historique");
        });
    }

    @Test
    void laisse_intact_un_message_sous_le_plafond() {
        memory.add("conv-1", new UserMessage("court"));

        assertThat(delegate.stored).singleElement()
                .satisfies(message -> assertThat(message.getText()).isEqualTo("court"));
    }

    @Test
    void delegue_la_lecture_et_la_purge() {
        memory.add("conv-1", new UserMessage("court"));

        assertThat(memory.get("conv-1")).hasSize(1);
        memory.clear("conv-1");
        assertThat(delegate.stored).isEmpty();
    }

    private static final class RecordingChatMemory implements ChatMemory {

        private final List<Message> stored = new ArrayList<>();

        @Override
        public void add(String conversationId, List<Message> messages) {
            stored.addAll(messages);
        }

        @Override
        public List<Message> get(String conversationId) {
            return List.copyOf(stored);
        }

        @Override
        public void clear(String conversationId) {
            stored.clear();
        }
    }
}
