// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.util.List;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * {@code MessageWindowChatMemory} borne un <em>nombre</em> de messages, jamais leur taille : une
 * trace d'exception collée dans la conversation, ou une réponse où le modèle recopie un tableau
 * entier, part telle quelle dans le prompt de chaque tour suivant jusqu'à sortir de la fenêtre.
 * Quarante messages sans plafond de taille ne bornent donc rien du tout.
 *
 * <p>Ce décorateur coupe à l'écriture, pas à la lecture : l'échange en cours voit le message
 * entier — c'est ce que l'appelant vient d'envoyer, ou ce que le modèle vient de répondre — et
 * seule sa relecture aux tours suivants est bornée. La coupe est dite explicitement dans le texte :
 * un contenu tronqué en silence se relit comme un contenu complet, et le modèle conclurait sur une
 * donnée amputée sans le savoir.
 */
class BoundedChatMemory implements ChatMemory {

    private final ChatMemory delegate;
    private final int maxCharacters;

    BoundedChatMemory(ChatMemory delegate, int maxCharacters) {
        this.delegate = delegate;
        this.maxCharacters = maxCharacters;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        delegate.add(conversationId, messages.stream().map(this::bounded).toList());
    }

    @Override
    public void add(String conversationId, Message message) {
        delegate.add(conversationId, bounded(message));
    }

    @Override
    public List<Message> get(String conversationId) {
        return delegate.get(conversationId);
    }

    @Override
    public void clear(String conversationId) {
        delegate.clear(conversationId);
    }

    /**
     * Seuls les deux types que l'advisor de mémoire écrit réellement sont reconstruits — il ne
     * persiste que le message utilisateur et les générations de l'assistant. Reconstruire un type
     * qui n'arrive jamais ici serait du code que rien n'exercerait.
     */
    private Message bounded(Message message) {
        String text = message.getText();
        if (text == null || text.length() <= maxCharacters) {
            return message;
        }
        String cut = truncate(text);
        return switch (message) {
            case UserMessage user -> user.mutate().text(cut).build();
            case AssistantMessage assistant -> assistant.mutate().content(cut).build();
            default -> message;
        };
    }

    private String truncate(String text) {
        return text.substring(0, maxCharacters)
                + "\n[… %d caractères coupés de l'historique …]".formatted(text.length() - maxCharacters);
    }
}
