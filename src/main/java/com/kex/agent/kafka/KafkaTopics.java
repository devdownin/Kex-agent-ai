// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.util.List;

import com.kex.agent.supervision.Coverage;

/**
 * @param unavailable raison pour laquelle la vue est vide — outil non configuré, serveur
 *                    injoignable, réponse illisible. Une liste vide sans motif se lirait
 *                    « aucun topic », ce qui est une affirmation qu'on n'a pas les moyens de faire
 */
public record KafkaTopics(List<KafkaTopic> topics, Coverage coverage, List<String> warnings,
                          boolean truncated, String unavailable) {

    public static KafkaTopics unavailable(String reason) {
        return new KafkaTopics(List.of(), Coverage.notReported(), List.of(), false, reason);
    }
}
