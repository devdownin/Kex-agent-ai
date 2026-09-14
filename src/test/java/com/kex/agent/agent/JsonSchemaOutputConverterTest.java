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
            "properties", Map.of("total", Map.of("type", "integer")));

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
}
