// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

/**
 * Un budget de requêtes par appelant. L'implémentation décide de sa portée : le processus, ou
 * toutes les répliques qui partagent la même base.
 */
public interface RateLimiter {

    /** @return {@code false} si l'appelant a épuisé son budget ; l'appel est alors refusé */
    boolean tryConsume(String principal);
}
