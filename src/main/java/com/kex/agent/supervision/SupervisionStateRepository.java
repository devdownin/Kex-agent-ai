// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * L'état de supervision que deux répliques ne peuvent pas se permettre de voir différemment.
 *
 * <p>Trois pièces seulement, et le critère qui les retient est le même : leur divergence n'est pas
 * un tableau de bord discordant, c'est une action fausse.
 *
 * <ul>
 *   <li><b>Les décisions</b> : une demande de validation créée par la réplique qui a lancé le
 *       cycle n'existait pour personne d'autre. Derrière un load balancer, l'opérateur qui
 *       approuve tombe une fois sur deux sur un {@code 404}, et rien ne dit que ce n'est pas la
 *       décision qui a expiré.</li>
 *   <li><b>La pause</b> : mise en pause sur une réplique, l'agent continuait d'analyser et d'agir
 *       depuis les autres. Le verrou de {@link SupervisionScheduler} empêche deux cycles
 *       simultanés, pas un cycle que quelqu'un croit avoir arrêté.</li>
 *   <li><b>Les fenêtres de maintenance</b> : déclarée sur une réplique, elle ne taisait les
 *       alertes que là. Un déploiement connu redevenait un incident — et une décision — depuis
 *       les autres.</li>
 * </ul>
 *
 * <p>Ce qui reste délibérément en mémoire du processus : cycles, anomalies brutes et relevés de
 * processus. Leur divergence se voit et ne coûte qu'un rafraîchissement ; les partager voudrait
 * dire écrire en base à chaque relevé de chaque cycle, pour un historique que l'audit double déjà
 * sur ce qui engage.
 */
interface SupervisionStateRepository {

    boolean paused();

    void paused(boolean paused);

    /** Remplace la fenêtre du processus s'il en avait déjà une. */
    void putMaintenance(MaintenanceWindow window);

    /** @return la fenêtre retirée, {@code null} si le processus n'en avait aucune */
    MaintenanceWindow removeMaintenance(String processId);

    /** Les fenêtres encore ouvertes à {@code now} ; celles qui ont expiré sont purgées au passage. */
    List<MaintenanceWindow> activeMaintenance(Instant now);

    /** Écrit une décision, ou remplace celle de même identifiant. */
    void store(Decision decision);

    Optional<Decision> decision(String id);

    /** De la plus récente à la plus ancienne, bornée par {@code historySize}. */
    List<Decision> decisions();

    /**
     * Réserve une décision à celui qui la tranche, une fois pour toutes.
     *
     * <p>Le verrou par décision de {@code SupervisionService} vit dans le processus : il suffisait
     * tant que la décision n'existait que là. Partagée, elle est désormais approuvable depuis
     * n'importe quelle réplique — et deux opérateurs qui cliquent en même temps sur deux écrans
     * différents exécuteraient l'action deux fois. Rien dans l'enchaînement lire-exécuter-écrire
     * ne l'en empêche.
     *
     * <p>Réservée avant d'agir, jamais après : détecter la course une fois l'outil MCP appelé ne
     * répare rien. Une réservation ne se relâche pas — la réplique qui tombe en pleine exécution
     * laisse une décision que personne ne peut plus trancher, et qui expire donc normalement.
     * Devoir re-décider est le bon biais pour une action sur un cluster de production ; l'exécuter
     * deux fois ne l'est pas.
     *
     * @return {@code false} si elle était déjà réservée
     */
    boolean claim(String id);
}
