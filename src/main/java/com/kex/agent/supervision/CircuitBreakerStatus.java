// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * Visible sans Prometheus : un opérateur qui ouvre le Control Center doit voir qu'une intégration
 * est en difficulté sans avoir à ouvrir un tableau de bord séparé pour le découvrir.
 */
public record CircuitBreakerStatus(String name, CircuitBreaker.State state) {
}
