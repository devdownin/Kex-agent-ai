// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
class SupervisionConfig {

    /**
     * Injectée plutôt qu'appelée en statique : l'expiration d'une demande de validation se teste
     * en avançant l'horloge, pas en attendant trente minutes.
     */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Défaut, mono-instance. {@link JdbcAuditConfig} le remplace sous {@code shared-memory}, seul
     * profil où un {@code JdbcTemplate} existe.
     */
    @Bean
    @ConditionalOnMissingBean(AuditRepository.class)
    AuditRepository inMemoryAuditRepository(SupervisionProperties properties) {
        return new InMemoryAuditRepository(properties.historySize());
    }

    /**
     * L'audit seul bascule sur Postgres avec la mémoire de conversation : c'est la pièce de
     * conformité, là où cycles, anomalies et décisions restent un tableau de bord qu'une réplique
     * peut se permettre de ne pas partager avec les autres.
     */
    @Configuration(proxyBeanMethods = false)
    @Profile("shared-memory")
    static class JdbcAuditConfig {

        @Bean
        AuditRepository jdbcAuditRepository(JdbcTemplate jdbcTemplate) {
            return new JdbcAuditRepository(jdbcTemplate);
        }
    }
}
