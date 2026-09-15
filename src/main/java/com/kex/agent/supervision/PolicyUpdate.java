// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.util.Map;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

/**
 * Mise à jour partielle de la politique : un champ absent reste inchangé. Les seuils et l'autonomie
 * se remplacent en entier, pas clé par clé — une fusion silencieuse laisserait une capacité
 * oubliée dans l'interface conserver un droit que son auteur croyait retiré.
 */
public record PolicyUpdate(ExecutionMode mode,
                           Map<Capability, Autonomy> autonomy,
                           @DecimalMin("0.0") @DecimalMax("1.0") Double confidenceThreshold,
                           Thresholds thresholds,
                           String reason) {
}
