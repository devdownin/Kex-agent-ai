// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.util.List;
import java.util.Map;

import com.kex.agent.mcp.McpServerInfo;
import com.kex.agent.mcp.McpToolCatalog;
import com.kex.agent.mcp.McpToolInfo;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ForecastReadinessServiceTest {
    @Test
    void readiness_requires_connection_five_tools_complete_catalogue_and_enrolled_series() {
        var catalog = mock(McpToolCatalog.class);
        var forecasts = mock(ForecastViewService.class);
        var service = new ForecastReadinessService(catalog, new KafkaProperties("kafka-explorer", "", "", 200), forecasts);
        when(catalog.servers()).thenReturn(List.of());
        when(forecasts.metrics()).thenReturn(ForecastViewService.Read.failed("Refusé"));
        assertThat(service.read().ready()).isFalse();
        assertThat(service.read().missingTools()).hasSize(5);
        var tools = List.of("kex_list_forecastable_metrics", "kex_metric_history", "kex_forecast_metric",
                "kex_list_predicted_threshold_breaches", "kex_get_forecast_quality").stream()
                .map(name -> new McpToolInfo(name, "", Map.of())).toList();
        when(catalog.servers()).thenReturn(List.of(new McpServerInfo("kafka-explorer", "explorer", "1", "1", true, "CLOSED", tools)));
        var json = JsonMapper.builder().build();
        when(forecasts.metrics()).thenReturn(new ForecastViewService.Read(json.readTree("[]"), json.readTree("{\"complete\":true}"), json.readTree("[]"), false, null));
        assertThat(service.read().ready()).isFalse();
        when(forecasts.metrics()).thenReturn(new ForecastViewService.Read(json.readTree("[{\"seriesId\":\"s1\"}]"), json.readTree("{\"complete\":true}"), json.readTree("[]"), false, null));
        assertThat(service.read().ready()).isTrue();
        when(catalog.servers()).thenThrow(new IllegalStateException("transport secret"));
        assertThat(service.read().connected()).isFalse();
    }
}
