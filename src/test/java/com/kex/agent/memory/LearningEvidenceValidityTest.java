// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LearningEvidenceValidityTest {
    private static final Instant NOW = Instant.parse("2026-10-04T06:00:00Z");
    private LearningEvidence evidence(String outcome, String source, Instant observed, Instant expires,
            List<String> contradictions, List<String> checks) {
        return new LearningEvidence(outcome, source, observed, expires, contradictions, "v1", null, null, checks);
    }
    @Test void only_complete_current_uncontradicted_verification_is_usable() {
        var valid = evidence("VERIFIED", "task:1", NOW, NOW.plusSeconds(60), null, List.of("postcondition"));
        assertThat(valid.usable(NOW)).isTrue();
        assertThat(valid.parameters()).isEmpty();
        assertThat(valid.preconditions()).isEmpty();
        assertThat(valid.usable(NOW.plusSeconds(60))).isFalse();
        assertThat(valid.usable(NOW.minusSeconds(1))).isFalse();
        for (var invalid : List.of(
                evidence("UNVERIFIED", "task:1", NOW, NOW.plusSeconds(60), null, List.of("check")),
                evidence("VERIFIED", null, NOW, NOW.plusSeconds(60), null, List.of("check")),
                evidence("VERIFIED", "task:1", null, NOW.plusSeconds(60), null, List.of("check")),
                evidence("VERIFIED", "task:1", NOW, null, null, List.of("check")),
                evidence("VERIFIED", "task:1", NOW, NOW.plusSeconds(60), List.of("incident:2"), List.of("check")),
                evidence("VERIFIED", "task:1", NOW, NOW.plusSeconds(60), null, null))) {
            assertThat(invalid.usable(NOW)).isFalse();
        }
    }
}
