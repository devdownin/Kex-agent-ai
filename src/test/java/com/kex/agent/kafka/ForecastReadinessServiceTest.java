// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.util.List;
import java.util.Map;

import com.kex.agent.mcp.McpToolCatalog;
import com.kex.agent.mcp.McpToolInfo;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ForecastReadinessServiceTest {
    private static final String CONNECTION = "kafka-explorer";
    private static final List<String> TOOLS = List.of("kex_list_forecastable_metrics", "kex_metric_history", "kex_forecast_metric",
            "kex_list_predicted_threshold_breaches", "kex_get_forecast_quality");
    private final McpToolCatalog catalog = mock(McpToolCatalog.class);
    private final ForecastViewService forecasts = mock(ForecastViewService.class);
    private final ForecastReadinessService service = new ForecastReadinessService(catalog,
            new KafkaProperties(CONNECTION, "", "", 200), forecasts);

    private void connected() {
        when(catalog.discoverTools(CONNECTION)).thenReturn(TOOLS.stream().map(name -> new McpToolInfo(name, "", Map.of())).toList());
        TOOLS.forEach(tool -> when(catalog.isToolAllowed(CONNECTION, tool)).thenReturn(true));
    }

    private void metrics(String data, boolean complete, boolean truncated) {
        var json = JsonMapper.builder().build();
        when(forecasts.metrics()).thenReturn(new ForecastViewService.Read(json.readTree(data),
                json.readTree("{\"complete\":" + complete + "}"), json.readTree("[]"), truncated, null));
    }

    @Test
    void transport_failure_is_not_a_missing_tool_and_does_not_leak_details() {
        when(catalog.discoverTools(CONNECTION)).thenThrow(new IllegalStateException("token=secret"));
        var result = service.read();
        assertThat(result.connected()).isFalse();
        assertThat(result.missingTools()).isEmpty();
        assertThat(result.checks()).hasSize(6).noneMatch(check -> check.state().equals("MISSING"));
        assertThat(result.toString()).doesNotContain("secret");
        verifyNoInteractions(forecasts);
    }

    @Test
    void unknown_connection_is_actionable_without_testing_tools() {
        when(catalog.discoverTools(CONNECTION)).thenThrow(new com.kex.agent.mcp.UnknownMcpServerException(CONNECTION));
        assertThat(service.read().unavailable()).isEqualTo("Connexion MCP inconnue");
        verifyNoInteractions(forecasts);
    }

    @Test
    void missing_and_forbidden_tools_have_different_remedies_and_skip_catalogue_read() {
        connected();
        when(catalog.discoverTools(CONNECTION)).thenReturn(TOOLS.subList(0, 4).stream().map(name -> new McpToolInfo(name, "", Map.of())).toList());
        when(catalog.isToolAllowed(CONNECTION, "kex_metric_history")).thenReturn(false);
        var result = service.read();
        assertThat(result.connected()).isTrue();
        assertThat(result.ready()).isFalse();
        assertThat(result.missingTools()).containsExactly("kex_get_forecast_quality");
        assertThat(result.checks()).anyMatch(check -> check.id().equals("kex_metric_history") && check.state().equals("BLOCKED") && check.action().contains("politique globale"));
        verifyNoInteractions(forecasts);
    }

    @Test
    void complete_visible_series_and_all_permissions_are_required() {
        connected(); metrics("[]", true, false);
        assertThat(service.read().ready()).isFalse();
        assertThat(service.read().checks()).anyMatch(check -> check.detail().equals("Aucune série autorisée visible"));
        metrics("[{\"seriesId\":\"s1\"}]", true, true);
        assertThat(service.read().ready()).isFalse();
        metrics("[{\"seriesId\":\"s1\"}]", false, false);
        assertThat(service.read().ready()).isFalse();
        metrics("[{\"seriesId\":\"s1\"}]", true, false);
        assertThat(service.read().ready()).isTrue();
        assertThat(service.read().seriesCount()).isEqualTo(1);
        assertThat(service.read().checkedAt()).isPositive();
    }

    @Test
    void remote_read_failure_keeps_tools_available_without_certifying_source_permissions() {
        connected(); when(forecasts.metrics()).thenReturn(ForecastViewService.Read.failed("Lecture refusée"));
        var result = service.read();
        assertThat(result.ready()).isFalse();
        assertThat(result.unavailable()).isEqualTo("Lecture refusée");
        assertThat(result.checks()).anyMatch(check -> check.id().equals("visible-series") && check.state().equals("BLOCKED"));
    }
}
