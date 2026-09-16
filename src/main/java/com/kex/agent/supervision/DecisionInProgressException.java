// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

/**
 * Une autre requête exécute déjà cette même décision. Refuser plutôt qu'attendre : la seconde
 * requête n'a rien à gagner à faire la queue, la décision qu'elle trouverait à son réveil ne
 * serait de toute façon plus en attente de validation.
 */
public class DecisionInProgressException extends RuntimeException {

    public DecisionInProgressException(String id) {
        super("La décision %s est déjà en cours de traitement".formatted(id));
    }
}
