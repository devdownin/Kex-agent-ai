// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.execution;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.mcp.McpToolCatalog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "kex.agent.tasks", name = "enabled", havingValue = "true")
class TaskConfig {
    @Bean @Profile("!shared-memory")
    TaskRepository fileTasks(ObjectMapper mapper, TaskProperties properties) {
        return new FileTaskRepository(mapper, Path.of(properties.storePath()));
    }
    @Bean @Profile("shared-memory")
    TaskRepository jdbcTasks(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new JdbcTaskRepository(jdbc, mapper);
    }
    @Bean TaskGateway taskGateway(McpToolCatalog catalog) {
        return new TaskGateway() {
            @Override public com.kex.agent.mcp.McpToolResult call(String connection, String tool,
                    java.util.Map<String, Object> arguments) { return catalog.call(connection, tool, arguments); }
            @Override public java.util.Map<String, Object> schema(String connection, String tool) {
                if (!catalog.isToolAllowed(connection, tool)) throw new TaskConflictException("Outil de tâche non autorisé");
                return catalog.servers().stream().filter(server -> server.connection().equals(connection))
                        .flatMap(server -> server.tools().stream()).filter(candidate -> candidate.name().equals(tool))
                        .findFirst().orElseThrow(() -> new TaskConflictException("Contrat de tâche indisponible")).inputSchema();
            }
        };
    }
}
