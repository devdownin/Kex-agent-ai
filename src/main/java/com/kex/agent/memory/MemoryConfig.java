// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Clock;
import java.nio.file.Path;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Allumée par défaut : contrairement à la base de connaissance, la mémoire n'exige aucune
 * infrastructure de plus que l'agent lui-même, et se désactive explicitement plutôt que par défaut.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "kex.agent.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
class MemoryConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Défaut, mono-instance. {@link JdbcMemoryConfig} le remplace sous {@code shared-memory}, seul
     * profil où un {@code JdbcTemplate} existe.
     */
    @Bean
    @ConditionalOnMissingBean(MemoryRepository.class)
    @Profile("!shared-memory")
    MemoryRepository inMemoryMemoryRepository(MemoryProperties properties, ObjectMapper mapper,
            @Value("${kex.agent.memory.storage-directory:${user.home}/.kex/memory}") String directory) {
        return new FileMemoryRepository(mapper, Path.of(directory, "facts.json"), properties.capacity());
    }

    @Bean
    MemoryService memoryService(MemoryRepository repository, MemoryProperties properties, Clock clock) {
        return new MemoryService(repository, properties, clock);
    }

    @Bean
    MemoryTools memoryTools(MemoryService memoryService) {
        return new MemoryTools(memoryService);
    }

    /** La mémoire bascule sur Postgres avec la mémoire de conversation et l'audit sous ce profil. */
    @Configuration(proxyBeanMethods = false)
    @Profile("shared-memory")
    static class JdbcMemoryConfig {

        @Bean
        MemoryRepository jdbcMemoryRepository(JdbcTemplate jdbcTemplate, MemoryProperties properties) {
            return new JdbcMemoryRepository(jdbcTemplate, properties.capacity());
        }
    }
}
