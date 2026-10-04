// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.knowledge;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeBoundaryTest {
    private static final Instant NOW = Instant.parse("2026-10-04T06:00:00Z");
    private static final KnowledgeAccess ACCESS = new KnowledgeAccess("tenant", Set.of("CHAT"));
    private KnowledgeService service(VectorStore store) {
        return new KnowledgeService(store, new KnowledgeProperties(true, 4, 0.5, ""), Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private Map<String, Object> metadata() {
        return new HashMap<>(Map.of("owner", "tenant", "environment", "default", "readRole", "TENANT",
                "observedEpoch", NOW.toEpochMilli(), "validUntilEpoch", NOW.plusSeconds(60).toEpochMilli(), "source", "runbook:1"));
    }
    @Test void independently_rejects_bad_documents_even_if_the_vector_backend_ignores_filters() {
        var store = mock(VectorStore.class); var service = service(store);
        for (var patch : List.<Map<String, Object>>of(Map.of("owner", "other"), Map.of("environment", "prod"),
                Map.of("readRole", "ADMIN"), Map.of("observedEpoch", "invalid"),
                Map.of("observedEpoch", NOW.plusMillis(1).toEpochMilli()),
                Map.of("observedEpoch", NOW.minusSeconds(31 * 86400).toEpochMilli()),
                Map.of("validUntilEpoch", "invalid"), Map.of("validUntilEpoch", NOW.toEpochMilli()),
                Map.of("source", 42), Map.of("source", " "))) {
            var m = metadata(); m.putAll(patch);
            when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(Document.builder().text("secret").metadata(m).build()));
            assertThat(service.search("topic", null, ACCESS)).isEmpty();
        }
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(Document.builder().text("legacy").build()));
        assertThat(service.search("topic", 1, ACCESS)).isEmpty();
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(null);
        assertThat(service.context("topic", ACCESS)).isEmpty();
    }
    @Test void rejects_invalid_queries_and_ingestion_metadata_without_touching_the_store() {
        var store = mock(VectorStore.class); var service = service(store);
        assertThatThrownBy(() -> service.search(null, 1, ACCESS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(" ", 1, ACCESS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search("topic", 101, ACCESS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.add(null, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.add(" ", List.of())).isInstanceOf(IllegalArgumentException.class);
        for (var metadata : List.<Map<String, Object>>of(Map.of("source", " "), Map.of("environment", " "), Map.of("readRole", "SUPERUSER"))) {
            assertThatThrownBy(() -> service.add("tenant", List.of(new KnowledgeDocument("topic", metadata))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(store);
    }
    @Test void ranks_fresh_evidence_ahead_of_equal_similarity_and_keeps_a_dated_reference() {
        var store = mock(VectorStore.class); var service = service(store);
        var old = metadata(); old.put("observedEpoch", NOW.minusSeconds(86400).toEpochMilli());
        var fresh = metadata(); fresh.put("observedAt", NOW.toString()); fresh.put("validUntil", NOW.plusSeconds(60).toString());
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder().id("old").text("old evidence").metadata(old).score(0.9).build(),
                Document.builder().id("fresh").text("fresh evidence").metadata(fresh).score(0.9).build()));
        var matches = service.search("topic", 1, ACCESS);
        assertThat(matches).extracting(KnowledgeMatch::id).containsExactly("fresh");
        assertThat(service.contextFrom(matches)).contains("[source:fresh]", "runbook:1", NOW.toString(), "fresh evidence");
    }
}
