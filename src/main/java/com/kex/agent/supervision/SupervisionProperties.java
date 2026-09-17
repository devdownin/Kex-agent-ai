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

        /**
         * Plancher de confiance propre à une capacité. Il ne peut que relever le plancher global :
         * « redémarrer un consumer » mérite plus de certitude que « notifier ».
         */
        @DefaultValue Map<Capability, Double> confidenceThresholds,

        @DefaultValue Map<Capability, ActionBinding> actions,

        /** Rétention en mémoire. Au-delà, les entrées les plus anciennes sortent. */
        @DefaultValue("200") int historySize,

        /**
         * Délai d'expiration d'une demande de validation. Une action approuvée trois heures après
         * les faits agirait sur une situation qui n'existe plus.
         */
        @DefaultValue("30m") java.time.Duration approvalTimeout,

        /** Au-delà, l'interface signale des données potentiellement obsolètes. */
        @DefaultValue("15m") java.time.Duration staleAfter,

        @DefaultValue Schedule schedule,

        @DefaultValue AutoAdjust autoAdjust,

        @DefaultValue Correlation correlation,

        /**
         * Une capacité sans outil MCP lié échoue par défaut ({@code FAILED}) plutôt que de
         * s'exécuter à moitié. Ce drapeau la laisse à la place se résoudre en {@code SIMULATED} —
         * ce que l'agent aurait fait, sans le faire — pour calibrer la confiance et l'autonomie
         * avant qu'un exécuteur réel n'existe.
         */
        @DefaultValue("false") boolean simulateUnboundActions) {

    /**
     * Départ autonome du cycle, sans clic. Non actif par défaut, et actif seulement sous le profil
     * {@code shared-memory} ({@link SupervisionScheduleConfig}) : le verrou qui empêche deux
     * répliques de lancer le même cycle vit dans la base de la mémoire partagée, la seule qui existe
     * dans les deux cas.
     *
     * @param interval      délai entre deux tentatives — une tentative, pas forcément un cycle : une
     *                      réplique qui ne tient pas le verrou repart aussitôt
     * @param lockAtMostFor durée après laquelle le verrou expire de lui-même. Sans elle, une réplique
     *                      qui tombe en cours de cycle laisserait le verrou pris indéfiniment, et
     *                      plus aucune autre ne pourrait jamais relancer l'analyse
     */
    public record Schedule(@DefaultValue("false") boolean enabled,
                           @DefaultValue("5m") java.time.Duration interval,
                           @DefaultValue("10m") java.time.Duration lockAtMostFor) {
    }

    /**
     * Durcissement automatique d'un plancher de confiance par capacité, sur ses propres verdicts
     * humains. Ne peut que relever — jamais abaisser — comme toute règle sur
     * {@link SupervisionPolicy#confidenceThresholdOf}. Actif par défaut : il ne fait que resserrer,
     * jamais ouvrir, et une installation qui n'a pas encore de verdict humain ne voit rien changer.
     *
     * @param minSamples   décisions humaines minimum avant de tirer une conclusion — trois refus
     *                     sur trois ne disent encore rien d'une capacité tout juste activée
     * @param minRelevance en-deçà de ce taux d'approbation, le plancher de la capacité concernée
     *                     est relevé
     * @param increment    ce qui est ajouté à chaque relèvement, jamais au-delà de {@code 1.0}
     */
    public record AutoAdjust(@DefaultValue("true") boolean enabled,
                             @DefaultValue("5") int minSamples,
                             @DefaultValue("0.5") double minRelevance,
                             @DefaultValue("0.05") double increment) {
    }

    /**
     * Plusieurs processus distincts en anomalie au même cycle se lisent comme une cause commune —
     * voir {@link CorrelatedIncident}. Une heuristique volontairement grossière : la seule
     * concomitance, rien de plus.
     *
     * @param minProcesses nombre de processus distincts en anomalie dans le même cycle à partir
     *                     duquel un incident corrélé est signalé
     */
    public record Correlation(@DefaultValue("true") boolean enabled, @DefaultValue("3") int minProcesses) {
    }
}
