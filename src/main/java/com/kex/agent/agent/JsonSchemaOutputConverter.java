// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.converter.StructuredOutputConverter;

/**
 * Sortie structurée contre un schéma fourni par l'appelant. Les convertisseurs de Spring AI
 * partent d'un type Java ; ici le schéma n'est connu qu'à la requête, donc le format est décrit
 * au modèle à partir du schéma lui-même et la réponse est rendue telle quelle.
 */
class JsonSchemaOutputConverter implements StructuredOutputConverter<Map<String, Object>> {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String schema;

    JsonSchemaOutputConverter(Map<String, Object> schema) {
        try {
            this.schema = JSON.writeValueAsString(schema);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new InvalidJsonSchemaException("Schéma illisible : " + ex.getOriginalMessage());
        }
    }

    @Override
    public String getFormat() {
        return """
                Réponds uniquement par un objet JSON conforme au schéma ci-dessous, sans texte \
                autour et sans bloc de code markdown.
                %s""".formatted(schema);
    }

    /** Renseigné pour les fournisseurs qui contraignent le décodage plutôt que de l'espérer. */
    @Override
    public String getJsonSchema() {
        return schema;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> convert(String source) {
        try {
            return JSON.readValue(source, Map.class);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            // Le modèle n'a pas respecté le contrat : c'est une panne amont, pas une erreur
            // d'appelant, et la distinction compte pour qui lit les codes de retour.
            throw new StructuredOutputException(ex);
        }
    }
}
