// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Base de connaissance interrogée avant chaque échange. Éteinte par défaut : elle exige un modèle
 * d'embeddings, donc une infrastructure que l'agent n'impose pas pour démarrer.
 */
@ConfigurationProperties("kex.agent.knowledge")
public record KnowledgeProperties(

        @DefaultValue("false") boolean enabled,

        /** Nombre de passages injectés dans le prompt. Au-delà, on paie du contexte pour du bruit. */
        @DefaultValue("4") int topK,

        /** Seuil de similarité : un passage sans rapport vaut moins que pas de passage du tout. */
        @DefaultValue("0.6") double similarityThreshold,

        /** Fichier de persistance du magasin en mémoire. Vide : la connaissance meurt avec le processus. */
        @DefaultValue("") String storePath) {
}
