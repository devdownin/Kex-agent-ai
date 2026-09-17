// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Instant;
import java.util.List;

/**
 * Plusieurs processus distincts en anomalie au même cycle, groupés sous un seul signal plutôt que
 * dispersés en autant d'alertes séparées — le symptôme d'une cause commune (broker en panne,
 * cluster saturé) se lit très différemment d'un cycle qui accumule des incidents indépendants.
 *
 * <p>Une heuristique grossière et assumée comme telle : la seule concomitance dans un même cycle,
 * rien de plus. Elle ne prétend identifier aucune cause réelle — deviner une causalité précise à
 * partir d'une simple simultanéité serait plus trompeur que de ne rien dire.
 */
public record CorrelatedIncident(String cycleId, Instant detectedAt, int processCount,
                                 List<String> processNames, ProcessState severity, List<String> titles) {
}
