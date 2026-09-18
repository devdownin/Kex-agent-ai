// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonSchemaOutputConverterTest {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("total", Map.of("type", "integer")),
            "required", java.util.List.of("total"),
            "additionalProperties", false);

    @Test
    void decrit_le_schema_au_modele() {
        JsonSchemaOutputConverter converter = new JsonSchemaOutputConverter(SCHEMA);

        assertThat(converter.getFormat()).contains("\"type\":\"object\"").contains("total");
        // Renseigné aussi en tant que schéma : les fournisseurs qui contraignent le décodage
        // l'utilisent plutôt que d'espérer que le modèle lise la consigne.
        assertThat(converter.getJsonSchema()).contains("total");
    }

    @Test
    void rend_le_json_du_modele() {
        assertThat(new JsonSchemaOutputConverter(SCHEMA).convert("{\"total\": 8}"))
                .containsEntry("total", 8);
    }

    @Test
    void signale_une_reponse_non_conforme() {
        JsonSchemaOutputConverter converter = new JsonSchemaOutputConverter(SCHEMA);

        assertThatThrownBy(() -> converter.convert("je ne sais pas"))
                .isInstanceOf(StructuredOutputException.class);
    }

    @Test
    void refuse_un_objet_json_qui_ne_respecte_pas_le_schema() {
        JsonSchemaOutputConverter converter = new JsonSchemaOutputConverter(SCHEMA);

        assertThatThrownBy(() -> converter.convert("{\"total\":\"huit\"}"))
                .isInstanceOf(StructuredOutputException.class)
                .hasMessageContaining("conforme au schéma");
        assertThatThrownBy(() -> converter.convert("{}"))
                .isInstanceOf(StructuredOutputException.class);
        assertThatThrownBy(() -> converter.convert("{\"total\":8,\"inattendu\":true}"))
                .isInstanceOf(StructuredOutputException.class);
    }

    @Test
    void refuse_un_schema_non_objet_ou_une_reference_distante() {
        assertThatThrownBy(() -> new JsonSchemaOutputConverter(Map.of("type", "array")))
                .isInstanceOf(InvalidJsonSchemaException.class)
                .hasMessageContaining("type racine");
        assertThatThrownBy(() -> new JsonSchemaOutputConverter(Map.of(
                "type", "object", "$ref", "https://example.com/schema.json")))
                .isInstanceOf(InvalidJsonSchemaException.class)
                .hasMessageContaining("distantes");
    }
}
