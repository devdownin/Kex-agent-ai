// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

/**
 * Cycle de vie d'une décision. {@code BLOCKED} sépare « l'agent a recommandé mais n'a pas le droit »
 * de « l'agent attend un humain » : la première demande un changement de politique, la seconde un
 * clic.
 */
public enum DecisionStatus {

    PENDING_APPROVAL,
    EXECUTED,
    REJECTED,

    /** L'action est partie et a échoué : outil en erreur, ou aucun outil lié à la capacité. */
    FAILED,

    /**
     * Personne n'a répondu à temps. Distinct de {@code FAILED} : confondre « l'outil a échoué » et
     * « la demande est restée sans réponse » masquerait un défaut d'organisation en défaut technique.
     */
    EXPIRED,

    BLOCKED,

    /**
     * Aurait dû s'exécuter (autonomie suffisante, confiance atteinte) mais aucun outil MCP n'est lié
     * à la capacité — {@code kex.agent.supervision.simulate-unbound-actions} l'a laissée poursuivre
     * jusqu'ici plutôt que de la faire échouer. Distinct de {@code FAILED} : un outil bien lié qui
     * refuse l'appel est un vrai échec, une capacité jamais câblée n'en est pas un.
     */
    SIMULATED
}
