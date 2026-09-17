// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Duration;

/**
 * Ce qu'un processus précis règle différemment des seuils globaux. Champs nullables et sans
 * {@code @DefaultValue}, à l'inverse de {@link Thresholds} : un processus qui n'en déclare aucun
 * doit rester indiscernable d'un processus qui les a tous omis, pour hériter des seuils globaux
 * plutôt que de les recouvrir silencieusement par les mêmes valeurs par défaut pour tous.
 */
public record ThresholdOverrides(Long consumerLag, Double errorRatePercent, Duration processingTime,
                                 Long blockedMessages, Duration observationWindow) {
}
