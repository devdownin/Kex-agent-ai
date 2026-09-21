// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Même partage des rôles que pour la mémoire et l'audit : en mémoire du processus par défaut,
 * en base sous {@code shared-memory}, seul profil où un {@code JdbcTemplate} existe.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "kex.agent.rate-limit", name = "enabled", havingValue = "true",
        matchIfMissing = true)
class RateLimitConfig {

    @Bean
    @ConditionalOnMissingBean(RateLimiter.class)
    RateLimiter inMemoryRateLimiter(RateLimitProperties properties) {
        return new InMemoryRateLimiter(properties);
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("shared-memory")
    static class JdbcRateLimitConfig {

        @Bean
        RateLimiter jdbcRateLimiter(JdbcTemplate jdbcTemplate, Clock clock, RateLimitProperties properties) {
            return new JdbcRateLimiter(jdbcTemplate, clock, properties);
        }
    }
}
