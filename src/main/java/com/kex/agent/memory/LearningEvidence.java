// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Evidence comes from the deterministic executor, never from a model's success claim. */
public record LearningEvidence(String outcome, String sourceId, Instant observedAt, Instant validUntil,
        List<String> contradictions, String version, Map<String, Object> parameters,
        List<String> preconditions, List<String> checks) {
    public LearningEvidence {
        contradictions = contradictions == null ? List.of() : List.copyOf(contradictions);
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
        checks = checks == null ? List.of() : List.copyOf(checks);
    }
    public boolean usable(Instant now) {
        return "VERIFIED".equals(outcome) && sourceId != null && observedAt != null
                && !observedAt.isAfter(now) && validUntil != null && now.isBefore(validUntil)
                && contradictions.isEmpty() && !checks.isEmpty();
    }
}
