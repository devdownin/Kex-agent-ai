// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

/**
 * @param records        nombre d'enregistrements, non mesuré sur un topic que l'outil n'a pas pu lire
 * @param lastActivityMs horodatage du dernier message, même réserve
 * @param deadLetter     l'outil a reconnu une file de rebut
 */
public record KafkaTopic(String name, int partitions, MeasuredValue records, MeasuredValue lastActivityMs,
                         boolean deadLetter) {
}
