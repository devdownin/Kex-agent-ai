// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.knowledge;

import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

public class KnowledgeService {

    private final VectorStore vectorStore;
    private final KnowledgeProperties properties;

    public KnowledgeService(VectorStore vectorStore, KnowledgeProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    /** @return les identifiants attribués, pour pouvoir retirer ensuite ce qu'on a ajouté */
    public List<String> add(List<KnowledgeDocument> documents) {
        List<Document> converted = documents.stream()
                .map(document -> Document.builder()
                        .text(document.text())
                        .metadata(document.metadata() == null ? Map.of() : document.metadata())
                        .build())
                .toList();
        vectorStore.add(converted);
        return converted.stream().map(Document::getId).toList();
    }

    public void delete(List<String> ids) {
        vectorStore.delete(ids);
    }

    /** Même recherche que celle de l'advisor : sert à vérifier ce que le modèle verra. */
    public List<KnowledgeMatch> search(String query, Integer topK) {
        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK == null ? properties.topK() : topK)
                .similarityThreshold(properties.similarityThreshold())
                .build());
        return results == null ? List.of()
                : results.stream()
                        .map(document -> new KnowledgeMatch(document.getId(), document.getText(),
                                document.getMetadata(), document.getScore()))
                        .toList();
    }
}
