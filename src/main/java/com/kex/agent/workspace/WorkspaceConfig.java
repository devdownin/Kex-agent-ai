// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.workspace;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
class WorkspaceConfig {
    @Bean @Profile("!shared-memory")
    WorkspaceStore fileWorkspace(ObjectMapper mapper,
            @Value("${kex.agent.workspace.storage-directory:${user.home}/.kex/workspace}") String directory) {
        return new WorkspaceStore(mapper, Path.of(directory));
    }
    @Bean @Profile("shared-memory")
    WorkspaceStore sharedWorkspace(ObjectMapper mapper, JdbcTemplate jdbc) { return new WorkspaceStore(mapper, jdbc); }
}
