// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.knowledge;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.vectorstore.VectorStore;

import static org.assertj.core.api.Assertions.assertThat;

/** Sans persistance, tout réapprentissage se paierait à chaque redémarrage. */
class KnowledgePersistenceTest {

    private static final DeterministicEmbeddingModel EMBEDDINGS = new DeterministicEmbeddingModel();

    @Test
    void sauvegarde_a_l_arret_et_recharge_au_demarrage(@TempDir Path directory) throws Exception {
        // Sous-répertoire absent : le persister doit le créer, pas échouer.
        Path file = directory.resolve("nested/knowledge.json");
        KnowledgeProperties properties = new KnowledgeProperties(true, 4, 0.5, file.toString());
        KnowledgeConfig config = new KnowledgeConfig();

        VectorStore first = config.vectorStore(EMBEDDINGS, properties);
        new KnowledgeService(first, properties)
                .add(List.of(new KnowledgeDocument("La rétention des topics demo est de 7 jours.", null)));
        config.knowledgePersister(first, properties).destroy();

        assertThat(file).exists();

        VectorStore reloaded = config.vectorStore(EMBEDDINGS, properties);
        assertThat(new KnowledgeService(reloaded, properties).search("retention topic", null))
                .isNotEmpty();
    }

    @Test
    void sans_chemin_configure_rien_n_est_ecrit(@TempDir Path directory) throws Exception {
        KnowledgeProperties properties = new KnowledgeProperties(true, 4, 0.5, "");
        KnowledgeConfig config = new KnowledgeConfig();

        config.knowledgePersister(config.vectorStore(EMBEDDINGS, properties), properties).destroy();

        assertThat(directory).isEmptyDirectory();
    }
}
