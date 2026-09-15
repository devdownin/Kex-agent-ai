// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.util.List;

/**
 * Ce qu'un relevé a réellement couvert. Miroir de l'enveloppe {@code coverage} que portent les
 * outils MCP de Kafka SQL Explorer, et qui existe pour une raison précise : un résultat vide se
 * lit « ça n'existe pas » alors qu'il signifie souvent « je n'ai pas regardé ».
 *
 * <p>La règle qui en découle tient en une phrase : <b>un relevé partiel peut prouver une présence,
 * jamais une absence.</b> Une anomalie trouvée sur une passe incomplète reste une anomalie ; un
 * « tout va bien » sur une passe incomplète n'est pas un « tout va bien ».
 *
 * @param notReached ce qui n'a pas été lu, <b>nommé</b> et non compté : « 38 topics non lus »
 *                   laisse conclure que le reste était sans importance, la liste laisse voir que
 *                   celui dont il était question s'y trouve
 */
public record Coverage(boolean complete, StopReason stopReason, List<String> notReached, String detail) {

    /** Aucune enveloppe remontée : ni complet, ni déclaré incomplet. */
    public static Coverage notReported() {
        return new Coverage(false, StopReason.NOT_REPORTED, List.of(), null);
    }

    /**
     * Distingue « le relevé s'est arrêté avant la fin » de « on ne sait pas s'il est allé au bout ».
     * Seul le premier invalide une conclusion négative : le second n'autorise à rien conclure de
     * plus, mais n'autorise pas non plus à dégrader tout un tableau de bord dont les outils ne
     * portent simplement pas d'enveloppe.
     */
    public boolean knownIncomplete() {
        return !complete && stopReason != StopReason.NOT_REPORTED;
    }
}
