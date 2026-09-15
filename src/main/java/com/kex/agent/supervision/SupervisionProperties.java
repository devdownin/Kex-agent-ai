// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration de départ de la supervision. Ce qui se règle depuis l'interface (mode, autonomie,
 * seuils) n'est lu ici qu'au démarrage : la suite vit dans {@link SupervisionPolicy}, versionnée et
 * auditée. Un redémarrage revient donc à ces valeurs, ce que l'écran de configuration annonce.
 *
 * @param processes rien par défaut : l'agent ne devine pas ce qu'il faut surveiller, et un tableau
 *                  de bord vide est un état honnête là où des processus inventés ne le seraient pas
 * @param autonomy  fermé par défaut pour toute capacité non citée — même posture que l'API, qui
 *                  répond 503 sans clé plutôt que de s'ouvrir
 */
@ConfigurationProperties("kex.agent.supervision")
public record SupervisionProperties(

        @DefaultValue("true") boolean enabled,

        @DefaultValue List<MonitoredProcess> processes,

        @DefaultValue("SUPERVISED") ExecutionMode mode,

        /** En deçà, une action automatique repasse en validation humaine plutôt que de s'exécuter. */
        @DefaultValue("0.85") double confidenceThreshold,

        @DefaultValue Thresholds thresholds,

        @DefaultValue Map<Capability, Autonomy> autonomy,

        @DefaultValue Map<Capability, ActionBinding> actions,

        /** Rétention en mémoire. Au-delà, les entrées les plus anciennes sortent. */
        @DefaultValue("200") int historySize,

        /**
         * Délai d'expiration d'une demande de validation. Une action approuvée trois heures après
         * les faits agirait sur une situation qui n'existe plus.
         */
        @DefaultValue("30m") java.time.Duration approvalTimeout,

        /** Au-delà, l'interface signale des données potentiellement obsolètes. */
        @DefaultValue("15m") java.time.Duration staleAfter) {
}
