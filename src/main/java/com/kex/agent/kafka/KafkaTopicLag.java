// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.util.List;

import com.kex.agent.supervision.Coverage;

/**
 * Les groupes qui lisent un topic, et à quel point ils sont en retard.
 *
 * @param groupsExamined  groupes réellement relevés
 * @param groupsInCluster groupes existants. L'écart avec {@code groupsExamined} est la part que
 *                        l'outil n'a pas regardée — la taire ferait lire une liste courte comme
 *                        une liste complète
 */
public record KafkaTopicLag(String topic, List<KafkaGroupLag> groups, int groupsExamined,
                            int groupsInCluster, String worstVerdict, Coverage coverage,
                            List<String> warnings, boolean truncated, String unavailable) {

    /** Vue vide qui dit pourquoi elle l'est, plutôt qu'une liste vide qu'on lirait comme « rien ». */
    public static KafkaTopicLag unavailable(String topic, String reason) {
        return new KafkaTopicLag(topic, List.of(), 0, 0, null, Coverage.notReported(), List.of(), false, reason);
    }
}
