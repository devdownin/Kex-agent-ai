// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.knowledge;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeServiceTest {

    private static final KnowledgeProperties PROPERTIES = new KnowledgeProperties(true, 4, 0.5, "");

    private static KnowledgeService service() {
        VectorStore store = SimpleVectorStore.builder(new DeterministicEmbeddingModel()).build();
        return new KnowledgeService(store, PROPERTIES);
    }

    @Test
    void retrouve_un_passage_pertinent() {
        KnowledgeService service = service();
        service.add(List.of(
                new KnowledgeDocument("La rétention des topics demo est de 7 jours.", Map.of("source", "runbook")),
                new KnowledgeDocument("Le déploiement Flink se fait le mardi.", Map.of())));

        List<KnowledgeMatch> matches = service.search("quelle retention sur un topic ?", null);

        assertThat(matches).isNotEmpty();
        assertThat(matches.getFirst().text()).contains("rétention");
        assertThat(matches.getFirst().metadata()).containsEntry("source", "runbook");
    }

    @Test
    void rend_les_identifiants_pour_pouvoir_retirer() {
        KnowledgeService service = service();
        List<String> ids = service.add(List.of(new KnowledgeDocument("Le topic demo.orders porte les commandes.", null)));

        assertThat(ids).hasSize(1);
        service.delete(ids);

        assertThat(service.search("topic", null)).isEmpty();
    }

    @Test
    void ne_rend_rien_sous_le_seuil_de_similarite() {
        KnowledgeService service = service();
        service.add(List.of(new KnowledgeDocument("Le déploiement Flink se fait le mardi.", Map.of())));

        // Un passage sans rapport vaut moins que pas de passage du tout : il coûte du contexte
        // et oriente le modèle à côté.
        assertThat(service.search("comment renouveler un certificat ?", null)).isEmpty();
    }
}
