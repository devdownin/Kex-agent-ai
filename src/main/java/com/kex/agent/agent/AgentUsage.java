// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Ce que le fournisseur dit avoir consommé pour cette réponse-là. Les métriques Spring AI agrègent
 * déjà les jetons par modèle, jamais par conversation — une étiquette de conversation ferait
 * exploser la cardinalité —, si bien qu'imputer un coût à un échange précis était impossible.
 *
 * <p>Des {@code Integer}, pas des {@code int} : tous les fournisseurs ne rendent pas ces compteurs,
 * et une mesure absente n'est jamais zéro. Zéro affirmerait « rien consommé ».
 */
public record AgentUsage(Integer inputTokens, Integer outputTokens) {

    /**
     * {@code null} quand la réponse ne porte aucun compteur. {@link EmptyUsage} en fait partie :
     * il rend {@code 0} là où le fournisseur n'a rien dit, exactement la confusion que le type
     * nullable existe pour éviter.
     */
    static AgentUsage from(ChatResponse response) {
        ChatResponseMetadata metadata = response == null ? null : response.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        if (usage == null || usage instanceof EmptyUsage) {
            return null;
        }
        return new AgentUsage(usage.getPromptTokens(), usage.getCompletionTokens());
    }

    /** Les deux compteurs sont nullables séparément ; le budget n'a besoin que de leur somme. */
    long total() {
        return (inputTokens == null ? 0 : inputTokens) + (outputTokens == null ? 0 : outputTokens);
    }
}
