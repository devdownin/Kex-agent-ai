// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
class ForecastAssociationConfig {
    @Bean
    @Profile("!shared-memory")
    ForecastAssociationRepository fileForecastAssociations(ObjectMapper json,
            @Value("${kex.agent.forecasts.store-path:${user.home}/.kex-agent-ai/forecast-associations.json}") String path) {
        return new FileForecastAssociationRepository(json, Path.of(path));
    }

    @Bean
    @Profile("shared-memory")
    ForecastAssociationRepository jdbcForecastAssociations(JdbcTemplate jdbc, ObjectMapper json) {
        return new JdbcForecastAssociationRepository(jdbc, json);
    }
}
