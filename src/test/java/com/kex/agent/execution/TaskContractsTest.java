// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.execution;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskContractsTest {
    @Test void rejects_missing_required_resource_wrong_type_and_undeclared_arguments() {
        var schema = Map.<String, Object>of("type", "object", "properties", Map.of("topic", Map.of("type", "string")),
                "required", List.of("topic"), "additionalProperties", false);
        assertThatCode(() -> TaskContracts.validate(schema, Map.of("topic", "orders"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> TaskContracts.validate(schema, Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskContracts.validate(schema, Map.of("topic", 42))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskContracts.validate(schema, Map.of("topic", "orders", "destination", "outside"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejects_remote_schema_references_before_fetching_or_execution() {
        for (String reference : List.of("$ref", "$dynamicRef", "$recursiveRef")) {
            var schema = Map.<String, Object>of("type", "object", "properties", Map.of("topic", Map.of(reference, "http://127.0.0.1:1/private")));
            assertThatThrownBy(() -> TaskContracts.validate(schema, Map.of("topic", "orders")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("distante");
        }
    }
}
