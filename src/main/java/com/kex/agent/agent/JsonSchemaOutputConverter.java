// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.ai.converter.StructuredOutputConverter;

/**
 * Sortie structurée contre un schéma fourni par l'appelant. Les convertisseurs de Spring AI
 * partent d'un type Java ; ici le schéma n'est connu qu'à la requête, donc le format est décrit
 * au modèle à partir du schéma lui-même et la réponse est rendue telle quelle.
 */
class JsonSchemaOutputConverter implements StructuredOutputConverter<Map<String, Object>> {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SchemaRegistry SCHEMAS =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    private final String schema;
    private final Schema validator;

    JsonSchemaOutputConverter(Map<String, Object> schema) {
        try {
            JsonNode schemaNode = JSON.valueToTree(schema);
            rejectRemoteReferences(schemaNode);
            if (!"object".equals(schemaNode.path("type").asText())) {
                throw new IllegalArgumentException("le type racine doit être object");
            }
            this.schema = JSON.writeValueAsString(schemaNode);
            this.validator = SCHEMAS.getSchema(this.schema, InputFormat.JSON);
        }
        catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new InvalidJsonSchemaException("Schéma JSON invalide : " + ex.getMessage());
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
            JsonNode value = JSON.readTree(source);
            List<com.networknt.schema.Error> errors = validator.validate(source, InputFormat.JSON);
            if (!errors.isEmpty()) {
                String detail = errors.stream().map(com.networknt.schema.Error::getMessage).sorted()
                        .limit(3).collect(java.util.stream.Collectors.joining(" ; "));
                throw new StructuredOutputException(detail);
            }
            return JSON.convertValue(value, Map.class);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            // Le modèle n'a pas respecté le contrat : c'est une panne amont, pas une erreur
            // d'appelant, et la distinction compte pour qui lit les codes de retour.
            throw new StructuredOutputException(ex);
        }
    }

    /** Les références distantes transformeraient un schéma fourni par l'appelant en requête SSRF. */
    private static void rejectRemoteReferences(JsonNode node) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (isReference(entry.getKey()) && entry.getValue().isTextual()
                        && !entry.getValue().textValue().startsWith("#")) {
                    throw new IllegalArgumentException("les références JSON Schema distantes ne sont pas autorisées");
                }
                rejectRemoteReferences(entry.getValue());
            });
        }
        else if (node.isArray()) {
            node.forEach(JsonSchemaOutputConverter::rejectRemoteReferences);
        }
    }

    private static boolean isReference(String key) {
        return "$ref".equals(key) || "$dynamicRef".equals(key) || "$recursiveRef".equals(key);
    }
}
