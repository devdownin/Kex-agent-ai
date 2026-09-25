// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.nio.file.Path;
import java.time.Clock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

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

    /** Défaut mono-instance, remplacé sous {@code shared-memory} comme l'audit ci-dessous. */
    @Bean
    @ConditionalOnMissingBean(SupervisionStateRepository.class)
    SupervisionStateRepository inMemorySupervisionState(SupervisionProperties properties) {
        return new InMemorySupervisionStateRepository(properties.historySize());
    }

    @Bean
    @Profile("!shared-memory")
    ProcessDefinitionRepository fileProcessDefinitions(ObjectMapper objectMapper,
            @Value("${kex.agent.supervision.process-store-path:${user.home}/.kex-agent-ai/processes.json}")
            String path) {
        return new FileProcessDefinitionRepository(objectMapper, Path.of(path));
    }

    /**
     * Audit et état décisionnel basculent ensemble sur Postgres avec la mémoire de conversation :
     * l'audit parce que c'est la pièce de conformité, les décisions, la pause et les fenêtres de
     * maintenance parce qu'en multi-instance leur divergence ne se lit pas comme un écran
     * discordant mais comme une action fausse — voir {@link SupervisionStateRepository}. Cycles,
     * anomalies et relevés restent, eux, un tableau de bord qu'une réplique peut se permettre de
     * ne pas partager.
     */
    @Configuration(proxyBeanMethods = false)
    @Profile("shared-memory")
    static class JdbcAuditConfig {

        @Bean
        ProcessDefinitionRepository jdbcProcessDefinitions(JdbcTemplate jdbcTemplate) {
            return new JdbcProcessDefinitionRepository(jdbcTemplate);
        }

        @Bean
        AuditRepository jdbcAuditRepository(JdbcTemplate jdbcTemplate) {
            return new JdbcAuditRepository(jdbcTemplate);
        }

        @Bean
        SupervisionStateRepository jdbcSupervisionState(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                                       Clock clock, SupervisionProperties properties) {
            return new JdbcSupervisionStateRepository(jdbcTemplate, objectMapper, clock,
                    properties.historySize());
        }
    }

    /**
     * Le cycle ne part seul que là où un verrou partagé existe pour empêcher deux répliques de le
     * lancer ensemble — voir {@link SupervisionScheduler}. Éteint par défaut ({@code
     * kex.agent.supervision.schedule.enabled}) : une installation existante ne se met pas à agir
     * sans qu'on le lui ait demandé.
     */
    @Configuration(proxyBeanMethods = false)
    @Profile("shared-memory")
    @ConditionalOnProperty(prefix = "kex.agent.supervision.schedule", name = "enabled", havingValue = "true")
    @EnableScheduling
    static class SupervisionScheduleConfig {

        @Bean
        SupervisionScheduler supervisionScheduler(SupervisionService supervision, JdbcTemplate jdbcTemplate,
                                                  Clock clock, SupervisionProperties properties) {
            return new SupervisionScheduler(supervision, jdbcTemplate, clock,
                    properties.schedule().lockAtMostFor(), properties.schedule().adaptive());
        }
    }
}
