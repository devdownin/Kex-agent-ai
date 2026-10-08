// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Bind the actual Compose environment through Spring, without Docker or a model. */
class ForecastComposeBindingTest {
    @Test
    void explorerEnvironmentBindsLocalTlsAndForecastDependencies() {
        Map<String, Object> variables = new HashMap<>();
        for (String file : new String[] {"docker-compose.yml", "compose/forecasts.yml"}) {
            var yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new FileSystemResource(file));
            var properties = yaml.getObject();
            String prefix = "services.explorer.environment.";
            properties.forEach((key, value) -> {
                String name = key.toString();
                if (name.startsWith(prefix)) variables.put(name.substring(prefix.length()), value.toString());
            });
        }
        // Compose resolves this operator setting before passing the environment to Spring.
        variables.replaceAll((key, value) -> value.equals("${FORECAST_ENVIRONMENTS:-local}") ? "local" : value);
        var environment = new StandardEnvironment();
        // Use the standard source name so Boot applies environment-variable name mapping.
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        var binder = Binder.get(environment);
        assertEquals(false, binder.bind("explorer.mcp.require-tls", Boolean.class).get());
        assertEquals("http://timesfm:8000", binder.bind("explorer.forecasting.inference.service-url", String.class).get());
        assertEquals("jdbc:postgresql://forecast-postgres:5432/forecasts", binder.bind("explorer.forecasting.history.jdbc-url", String.class).get());
        assertEquals("local", binder.bind("explorer.mcp.allowed-forecast-environments", String.class).get());
    }
}
