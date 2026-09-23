// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.server;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;

/** Protocol catalog kept separate from HTTP dispatch so descriptors can evolve independently. */
final class McpServerCatalog {
    static final Map<String, Object> EMPTY_SCHEMA = Map.of(
            "type", "object", "properties", Map.of(), "additionalProperties", false);
    static final Map<String, Object> KAFKA_TOPICS_SCHEMA = objectSchema(Map.of(
            "topics", arraySchema(objectSchema(Map.of(
                    "name", stringSchema(), "partitions", integerSchema(), "deadLetter", booleanSchema()))),
            "truncated", booleanSchema()));
    static final Map<String, Object> KAFKA_LAG_SCHEMA = objectSchema(Map.of(
            "topic", stringSchema(), "groupsExamined", integerSchema(), "groupsInCluster", integerSchema(),
            "worstVerdict", stringSchema(), "groups", arraySchema(objectSchema(Map.of(
                    "groupId", stringSchema(), "state", stringSchema(), "type", stringSchema(),
                    "partitionsWithoutCommit", integerSchema(), "verdict", stringSchema())))));
    static final Map<String, Object> PROCESS_DIAGNOSIS_SCHEMA = objectSchema(Map.of(
            "processId", stringSchema(), "snapshot", objectSchema(),
            "alerts", arraySchema(objectSchema()), "decisions", arraySchema(objectSchema())));

    static Map<String, Object> tool(String name, String description, Map<String, Object> outputSchema) {
        return Map.of("name", name, "description", description, "inputSchema", EMPTY_SCHEMA,
                "outputSchema", outputSchema, "annotations", Map.of("readOnlyHint", true,
                        "destructiveHint", false, "idempotentHint", true, "openWorldHint", false));
    }

    static Map<String, Object> toolWithInput(String name, String description,
                                             Map<String, Object> inputSchema, Map<String, Object> outputSchema) {
        return Map.of("name", name, "description", description, "inputSchema", inputSchema,
                "outputSchema", outputSchema, "annotations", Map.of("readOnlyHint", true,
                        "destructiveHint", false, "idempotentHint", true, "openWorldHint", false));
    }

    static Map<String, Object> resource(String uri, String name) {
        return Map.of("uri", uri, "name", name, "mimeType", MediaType.APPLICATION_JSON_VALUE);
    }

    static Map<String, Object> schema(Object... entries) {
        Map<String, Object> schema = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) schema.put((String) entries[i], entries[i + 1]);
        return Map.copyOf(schema);
    }
    static Map<String, Object> objectSchema() { return Map.of("type", "object"); }
    static Map<String, Object> objectSchema(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties);
    }
    static Map<String, Object> arraySchema(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }
    static Map<String, Object> stringSchema() { return Map.of("type", "string"); }
    static Map<String, Object> booleanSchema() { return Map.of("type", "boolean"); }
    static Map<String, Object> integerSchema() { return Map.of("type", "integer"); }
    static Map<String, Object> numberSchema() { return Map.of("type", "number"); }

    private McpServerCatalog() {}
}
