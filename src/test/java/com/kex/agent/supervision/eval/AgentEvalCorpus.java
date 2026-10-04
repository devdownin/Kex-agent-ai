// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision.eval;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Versioned network fixtures and independently declared observable outcomes. */
public final class AgentEvalCorpus {
    public static final String VERSION = "kex-supervision-v1";
    static final ObjectMapper JSON = new ObjectMapper();

    private AgentEvalCorpus() {
    }

    public static List<Scenario> load() throws IOException {
        try (var input = AgentEvalCorpus.class.getResourceAsStream("/evals/agent-scenarios.json")) {
            if (input == null) throw new IOException("Missing agent evaluation corpus");
            List<Scenario> scenarios = JSON.readValue(input, new TypeReference<>() { });
            if (scenarios.isEmpty()) throw new IOException("Empty agent evaluation corpus");
            Set<String> ids = new java.util.HashSet<>();
            for (Scenario scenario : scenarios) {
                if (!ids.add(scenario.id()) || scenario.allowedStates().isEmpty()
                        || scenario.requiredTools().isEmpty() || scenario.tools().isEmpty()
                        || !scenario.responses().keySet().containsAll(scenario.requiredTools())) {
                    throw new IOException("Invalid evaluation scenario: " + scenario.id());
                }
            }
            return List.copyOf(scenarios);
        }
    }

    public record Scenario(String id, String risk, List<Map<String, Object>> tools,
                           Map<String, Map<String, Object>> responses, List<String> requiredTools,
                           List<String> allowedTools, List<String> allowedStates,
                           Boolean completeCoverage, boolean requiresUnreachedOrders,
                           boolean requiresAnomaly, boolean forbidsAnomaly,
                           Map<String, Object> requiredArguments) {
    }
}
