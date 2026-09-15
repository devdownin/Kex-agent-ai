// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

/**
 * Ce que la supervision a besoin de savoir du modèle : peut-il seulement répondre.
 *
 * <p>Une interface d'une méthode plutôt qu'une dépendance sur la lecture complète de la
 * configuration : la supervision n'a que faire du modèle retenu ni de sa température, et l'étroitesse
 * du contrat est ce qui rend la règle testable sans monter un {@code Environment}.
 */
@FunctionalInterface
public interface ModelAvailability {

    /**
     * {@code true} seulement quand l'absence est <em>établie</em>. Un fournisseur dont on ne sait
     * pas lire la configuration n'accuse rien : dégrader sur une ignorance rendrait l'indicateur
     * aussi faux dans l'autre sens.
     */
    boolean keyKnownMissing();
}
