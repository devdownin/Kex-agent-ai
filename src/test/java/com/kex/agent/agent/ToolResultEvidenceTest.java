// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolResultEvidenceTest {
    @Test
    void les_preuves_sont_datees_et_masquees_avant_diffusion() {
        var recorder = new ToolCallRecorder();
        var context = new ToolContext(Map.of(ToolCallRecorder.CONTEXT_KEY, recorder));
        String result = "{\"lag\":12,\"nested\":{\"apiKey\":\"hidden\"},\"label\":\"Bearer private\"}";
        assertThat(ToolCallRecorder.timed(context, "lag", () -> result)).isEqualTo(result);
        assertThat(recorder.calls()).singleElement().satisfies(call -> {
            assertThat(call.tool()).isEqualTo("lag");
            assertThat(call.failed()).isFalse();
            assertThat(Instant.parse(call.observedAt())).isBeforeOrEqualTo(Instant.now());
            assertThat(call.result()).contains("\"lag\":12", "[Masqué]").doesNotContain("hidden", "private");
        });
    }

    @Test
    void une_erreur_ne_produit_pas_de_fausse_preuve() {
        var recorder = new ToolCallRecorder();
        var context = new ToolContext(Map.of(ToolCallRecorder.CONTEXT_KEY, recorder));
        assertThatThrownBy(() -> ToolCallRecorder.timed(context, "lag", () -> { throw new IllegalStateException("failure"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(recorder.calls()).singleElement().satisfies(call -> {
            assertThat(call.failed()).isTrue();
            assertThat(call.result()).isNull();
        });
    }

    @Test
    void les_contenus_non_structures_et_volumineux_ne_sont_pas_persistes() {
        assertThat(ToolResultEvidence.sanitize("texte contenant des secrets")).isNull();
        assertThat(ToolResultEvidence.sanitize("x".repeat(4001))).isNull();
        assertThat(ToolResultEvidence.sanitize(null)).isNull();
        assertThat(ToolResultEvidence.sanitize(42)).isNull();
        assertThat(ToolResultEvidence.sanitize("{\"password\":\"hidden\",\"rows\":[true,null,3]}"))
                .isEqualTo("{\"password\":\"[Masqué]\",\"rows\":[true,null,3]}");
    }
}
