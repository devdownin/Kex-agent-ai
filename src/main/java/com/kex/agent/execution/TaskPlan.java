// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.execution;

import java.util.List;
import java.util.Map;

public record TaskPlan(String objective, List<String> preconditions, List<Step> steps) {
    public TaskPlan {
        preconditions = preconditions == null ? null : List.copyOf(preconditions);
        steps = steps == null ? null : List.copyOf(steps);
    }
    public record Step(String id, String description, String binding, Map<String, Object> arguments,
            List<String> dependsOn, Expectation expectation) {
        public Step {
            arguments = arguments == null ? null : immutable(arguments);
            dependsOn = dependsOn == null ? null : List.copyOf(dependsOn);
        }
    }
    public record Expectation(String pointer, Object expected) {}
    private static Map<String, Object> immutable(Map<String, Object> values) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(key, freeze(value)));
        return java.util.Collections.unmodifiableMap(copy);
    }
    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new java.util.LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(String.valueOf(key), freeze(nested)));
            return java.util.Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) return list.stream().map(TaskPlan::freeze).toList();
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        throw new IllegalArgumentException("Arguments JSON uniquement");
    }
}
