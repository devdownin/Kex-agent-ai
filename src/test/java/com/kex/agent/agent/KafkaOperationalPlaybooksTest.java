// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaOperationalPlaybooksTest {
    @Test
    void routes_combined_incident_to_relevant_evidence_without_reprocessing() {
        String procedure = KafkaOperationalPlaybooks.forRequest(
                "Pourquoi le lag augmente et la DLQ se remplit ?");
        assertThat(procedure).contains("kex_consumer_lag_trend", "kex_dlq_review",
                "topic source explicite", "Ne pas retraiter automatiquement");
        assertThat(procedure).doesNotContain("kex_topic_configuration");
    }

    @Test
    void unrelated_question_does_not_receive_kafka_procedure() {
        assertThat(KafkaOperationalPlaybooks.forRequest("Quel est mon calendrier demain ?")).isEmpty();
    }

    @Test
    void policy_review_requires_an_environment_and_distinguishes_declared_dlq_links() {
        String procedure = KafkaOperationalPlaybooks.forRequest("Audit de configuration des topics et de la DLQ");
        assertThat(procedure).contains("kex_topic_policy_review", "nom", "NOT_CONFIGURED",
                "kex_dlq_review", "déclarative", "Ne pas retraiter automatiquement");
    }
}
