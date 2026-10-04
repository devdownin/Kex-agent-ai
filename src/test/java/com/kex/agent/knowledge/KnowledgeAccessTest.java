// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.knowledge;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeAccessTest {
    private static final Instant NOW = Instant.parse("2026-10-04T06:00:00Z");
    private KnowledgeService service() {
        return new KnowledgeService(SimpleVectorStore.builder(new DeterministicEmbeddingModel()).build(),
                new KnowledgeProperties(true, 4, 0.5, ""), Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }
    @Test void filters_tenant_role_environment_and_freshness_before_ranking() {
        var service = service();
        String text = "La rétention des topics demo est de 7 jours.";
        service.add("other", List.of(new KnowledgeDocument(text, Map.of())));
        service.add("one", List.of(new KnowledgeDocument(text, Map.of("readRole", "ADMIN")),
                new KnowledgeDocument(text, Map.of("environment", "prod")),
                new KnowledgeDocument(text, Map.of("observedAt", "2026-01-01T00:00:00Z")),
                new KnowledgeDocument(text, Map.of("validUntil", "2026-10-03T00:00:00Z")),
                new KnowledgeDocument(text, Map.of("owner", "other", "source", "runbook:retention"))));
        var access = new KnowledgeAccess("one", Set.of("CHAT"));
        var matches = service.search("retention topic", 1, access);
        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().metadata()).containsEntry("source", "runbook:retention").containsEntry("owner", "one");
        assertThat(service.context("retention topic", access)).contains("[source:" + matches.getFirst().id() + "]", NOW.toString());
        assertThat(service.search("retention topic", 10, new KnowledgeAccess("one", Set.of("ADMIN")))).hasSize(2);
        assertThatThrownBy(() -> service.search("retention topic", 0, access)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejects_future_dated_evidence() {
        assertThatThrownBy(() -> service().add("one", List.of(new KnowledgeDocument("topic", Map.of(
                "observedAt", "2027-01-01T00:00:00Z"))))).isInstanceOf(IllegalArgumentException.class);
    }
}
