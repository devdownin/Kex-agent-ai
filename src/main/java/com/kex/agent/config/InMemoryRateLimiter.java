// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Un seau par appelant, dans le processus. Deux répliques accordent donc chacune le seuil complet :
 * c'est le comportement historique, et il reste le défaut — la plupart des installations n'ont
 * qu'une instance, et leur faire payer une base pour compter des requêtes n'aurait aucun sens.
 * {@link JdbcRateLimiter} prend le relais là où une base existe déjà.
 */
class InMemoryRateLimiter implements RateLimiter {

    private final RateLimitProperties properties;
    private final Map<String, TokenBucket> bucketsByPrincipal = new ConcurrentHashMap<>();

    InMemoryRateLimiter(RateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean tryConsume(String principal) {
        return bucketsByPrincipal
                .computeIfAbsent(principal, name -> new TokenBucket(properties.burst(), properties.requestsPerMinute()))
                .tryConsume();
    }
}
