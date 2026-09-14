// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.knowledge;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Tout est conditionné à {@code kex.agent.knowledge.enabled} : sans modèle d'embeddings sur le
 * classpath, déclarer ces beans ferait échouer le démarrage de l'application entière — la même
 * erreur que le starter JDBC de la mémoire partagée.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "kex.agent.knowledge", name = "enabled", havingValue = "true")
class KnowledgeConfig {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeConfig.class);

    /**
     * {@link SimpleVectorStore} par défaut : la connaissance d'un agent tient souvent en quelques
     * centaines de passages, et une base vectorielle dédiée serait de l'infrastructure pour rien.
     * Déclarer un autre {@link VectorStore} le remplace sans toucher au reste.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(VectorStore.class)
    VectorStore vectorStore(EmbeddingModel embeddingModel, KnowledgeProperties properties) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        File file = file(properties);
        if (file != null && file.isFile()) {
            store.load(file);
            log.info("Connaissance rechargée depuis {}", file);
        }
        return store;
    }

    /** Sauvegarde à l'arrêt : sans cela, tout réapprentissage se paie à chaque redémarrage. */
    @Bean
    DisposableBean knowledgePersister(VectorStore vectorStore, KnowledgeProperties properties) {
        return () -> {
            File file = file(properties);
            if (file == null || !(vectorStore instanceof SimpleVectorStore store)) {
                return;
            }
            try {
                File parent = file.getAbsoluteFile().getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Répertoire non créé : " + parent);
                }
                store.save(file);
            }
            catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        };
    }

    @Bean
    KnowledgeService knowledgeService(VectorStore vectorStore, KnowledgeProperties properties) {
        return new KnowledgeService(vectorStore, properties);
    }

    @Bean
    Advisor questionAnswerAdvisor(VectorStore vectorStore, KnowledgeProperties properties) {
        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(properties.topK())
                        .similarityThreshold(properties.similarityThreshold())
                        .build())
                .build();
    }

    private static File file(KnowledgeProperties properties) {
        return StringUtils.hasText(properties.storePath()) ? new File(properties.storePath()) : null;
    }
}
