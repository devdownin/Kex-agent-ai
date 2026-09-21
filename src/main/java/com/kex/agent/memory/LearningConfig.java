// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.nio.file.Path;
import java.time.Clock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.skills.CharterService;
import com.kex.agent.skills.RefusalSkillProposer;
import com.kex.agent.skills.SkillCurator;
import com.kex.agent.skills.SkillsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "kex.agent.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
class LearningConfig {
    @Bean
    @Profile("!shared-memory")
    LearningRepository fileLearningRepository(ObjectMapper mapper, MemoryProperties properties,
            @Value("${kex.agent.memory.storage-directory:${user.home}/.kex/memory}") String directory) {
        return new FileLearningRepository(mapper, Path.of(directory, "learning.json"), properties.capacity());
    }

    @Bean
    @Profile("shared-memory")
    LearningRepository jdbcLearningRepository(JdbcTemplate jdbc, MemoryProperties properties) {
        return new JdbcLearningRepository(jdbc, properties.capacity());
    }

    @Bean
    SkillsService skillsService(LearningRepository repository, Clock clock) {
        return new SkillsService(repository, clock);
    }

    @Bean
    SkillCurator skillCurator(SkillsService skills, MemoryProperties properties, Clock clock) {
        return new SkillCurator(skills, properties, clock);
    }

    /** Sans mémoire activée, personne n'écoute l'événement : les refus ne font que durcir le plancher. */
    @Bean
    RefusalSkillProposer refusalSkillProposer(SkillsService skills) {
        return new RefusalSkillProposer(skills);
    }

    @Bean
    CharterService charterService(LearningRepository repository, Clock clock) {
        return new CharterService(repository, clock);
    }

    @Bean
    LongTermMemoryService longTermMemoryService(LearningRepository repository, SkillsService skills,
                                                CharterService charter, MemoryProperties properties,
                                                Clock clock) {
        return new LongTermMemoryService(repository, skills, charter, properties, clock);
    }
}
