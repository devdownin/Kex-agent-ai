// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} borné à ce module plutôt que posé globalement : le cycle de supervision
 * a sa propre condition ({@code shared-memory}, verrou en base) et ne doit pas hériter de celle-ci.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "kex.mcp.health-check", name = "enabled", matchIfMissing = true)
class McpHealthCheckConfig {

    @Bean
    McpHealthCheckScheduler mcpHealthCheckScheduler(McpToolCatalog catalog) {
        return new McpHealthCheckScheduler(catalog);
    }
}
