// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.util.Map;

/**
 * Politique en vigueur, remplacée d'un bloc plutôt que modifiée champ par champ : une lecture ne
 * peut ainsi jamais voir un mode déjà changé avec des seuils qui ne le sont pas encore. La version
 * s'incrémente à chaque changement et se retrouve dans chaque décision et chaque entrée d'audit.
 */
public record SupervisionPolicy(String version, ExecutionMode mode, Map<Capability, Autonomy> autonomy,
                                double confidenceThreshold, Thresholds thresholds) {

    public Autonomy autonomyOf(Capability capability) {
        // Fermé par défaut : une capacité non citée dans la politique ne s'exécute pas.
        return autonomy.getOrDefault(capability, Autonomy.FORBIDDEN);
    }

    /**
     * Le mode ne peut que restreindre l'autonomie déclarée d'une capacité. Sans cette règle,
     * basculer le mode en automatique ouvrirait d'un coup des actions délibérément supervisées.
     */
    public Autonomy effectiveAutonomy(Capability capability) {
        Autonomy declared = autonomyOf(capability);
        return switch (mode) {
            case MANUAL -> declared == Autonomy.FORBIDDEN ? Autonomy.FORBIDDEN : Autonomy.SUPERVISED;
            case SUPERVISED -> declared == Autonomy.AUTOMATIC ? Autonomy.SUPERVISED : declared;
            case AUTOMATIC -> declared;
        };
    }
}
