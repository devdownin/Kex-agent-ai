// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.server;

import java.util.LinkedHashMap;
import java.util.Map;

import com.kex.agent.kafka.KafkaViewService;
import com.kex.agent.supervision.SupervisionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Operational readiness of the inbound MCP surface and the read-only services it exposes. */
@Component("kexMcpServer")
@ConditionalOnProperty(prefix = "kex.mcp.server", name = "enabled", havingValue = "true")
public class KexMcpServerHealthIndicator implements HealthIndicator {

    private final ObjectProvider<SupervisionService> supervision;
    private final ObjectProvider<KafkaViewService> kafka;

    public KexMcpServerHealthIndicator(ObjectProvider<SupervisionService> supervision,
                                       ObjectProvider<KafkaViewService> kafka) {
        this.supervision = supervision;
        this.kafka = kafka;
    }

    @Override
    public Health health() {
        boolean supervisionReady = supervision.getIfAvailable() != null;
        boolean kafkaReady = kafka.getIfAvailable() != null;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("server", "enabled");
        details.put("supervision", supervisionReady ? "ready" : "unavailable");
        details.put("kafkaView", kafkaReady ? "ready" : "unavailable");
        Health.Builder health = supervisionReady ? Health.up() : Health.down();
        if (!kafkaReady) health.withDetail("optionalDependency", "kafkaView");
        return health.withDetails(details).build();
    }
}
