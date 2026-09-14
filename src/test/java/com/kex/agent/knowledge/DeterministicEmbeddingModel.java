// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.knowledge;

import java.util.List;
import java.util.Locale;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Embeddings par sac de mots sur un vocabulaire fixe. Aucun modèle réel : ce qu'on veut vérifier
 * est le câblage — magasin, advisor, endpoints — pas la qualité sémantique d'un fournisseur.
 */
class DeterministicEmbeddingModel implements EmbeddingModel {

    private static final List<String> VOCABULARY = List.of("kafka", "topic", "dlq", "retention", "flink", "schema");

    /**
     * Dimension constante : sans elle, un texte ne contenant aucun mot du vocabulaire produit un
     * vecteur nul, que la similarité cosinus refuse. Faible (0,2) pour que deux textes sans mot
     * commun restent sous le seuil.
     */
    private static final float BIAS = 0.2f;

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        float[] vector = new float[VOCABULARY.size() + 1];
        for (int i = 0; i < VOCABULARY.size(); i++) {
            vector[i] = lower.contains(VOCABULARY.get(i)) ? 1f : 0f;
        }
        vector[VOCABULARY.size()] = BIAS;
        return vector;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new java.util.ArrayList<>();
        List<String> inputs = request.getInstructions();
        for (int i = 0; i < inputs.size(); i++) {
            embeddings.add(new Embedding(embed(inputs.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }
}
