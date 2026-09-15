// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

/**
 * @param verdict    verdict rendu par l'outil (CAUGHT_UP, BEHIND, STALLED...). On l'affiche tel
 *                   quel plutôt que de le recalculer depuis les nombres : c'est l'outil qui sait
 *                   qu'un lag sans membre assigné ne se résorbera pas tout seul
 * @param error      ce qui a empêché de relever ce groupe, {@code null} quand tout s'est bien passé
 */
public record KafkaGroupLag(String groupId, String state, String type, MeasuredValue recordLag,
                            MeasuredValue lagMillis, int partitionsWithoutCommit, String verdict,
                            String explanation, String error) {
}
