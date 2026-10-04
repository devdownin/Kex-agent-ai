// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.execution;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

/** Untrusted MCP schemas cannot trigger external reference resolution. */
final class TaskContracts {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private TaskContracts() {}
    static void validate(Map<String, Object> schema, Map<String, Object> arguments) {
        try {
            JsonNode contract = JSON.valueToTree(schema);
            if (!contract.isObject() || !"object".equals(contract.path("type").asText()) || contract.toString().length() > 32000) {
                throw new IllegalArgumentException("Contrat d'outil absent ou trop volumineux");
            }
            rejectReferences(contract);
            var validator = SCHEMAS.getSchema(contract.toString(), InputFormat.JSON);
            if (!validator.validate(JSON.writeValueAsString(arguments), InputFormat.JSON).isEmpty()) {
                throw new IllegalArgumentException("Paramètres non conformes au contrat de l'outil");
            }
        } catch (java.io.IOException ex) { throw new IllegalArgumentException("Contrat d'outil illisible", ex); }
    }
    private static void rejectReferences(JsonNode node) {
        if (node.isObject()) node.fields().forEachRemaining(entry -> {
            if ((entry.getKey().equals("$ref") || entry.getKey().equals("$dynamicRef") || entry.getKey().equals("$recursiveRef"))
                    && (!entry.getValue().isTextual() || !entry.getValue().asText().startsWith("#"))) {
                throw new IllegalArgumentException("Référence de schéma distante interdite");
            }
            rejectReferences(entry.getValue());
        });
        else if (node.isArray()) node.forEach(TaskContracts::rejectReferences);
    }
}
