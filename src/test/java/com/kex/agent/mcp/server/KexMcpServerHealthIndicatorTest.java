// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.server;

import java.util.Map;

import com.kex.agent.kafka.KafkaViewService;
import com.kex.agent.supervision.SupervisionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KexMcpServerHealthIndicatorTest {

    @Test
    void is_up_when_supervision_is_available_and_reports_optional_kafka_state() {
        var supervision = mock(SupervisionService.class);
        var beans = new StaticListableBeanFactory(Map.of("supervision", supervision));
        var health = new KexMcpServerHealthIndicator(
                beans.getBeanProvider(SupervisionService.class), beans.getBeanProvider(KafkaViewService.class), new McpClientSessionRegistry(java.time.Clock.systemUTC(), java.time.Duration.ofMinutes(30), java.time.Duration.ofMinutes(5)))
                .health();

        assertThat(health.getStatus()).isEqualTo(new Status("DEGRADED"));
        assertThat(health.getDetails()).containsEntry("server", "enabled")
                .containsEntry("supervision", "ready").containsEntry("kafkaView", "unavailable");
    }

    @Test
    void is_down_without_supervision() {
        var beans = new StaticListableBeanFactory();
        var health = new KexMcpServerHealthIndicator(
                beans.getBeanProvider(SupervisionService.class), beans.getBeanProvider(KafkaViewService.class), new McpClientSessionRegistry(java.time.Clock.systemUTC(), java.time.Duration.ofMinutes(30), java.time.Duration.ofMinutes(5)))
                .health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("supervision", "unavailable");
    }
}
