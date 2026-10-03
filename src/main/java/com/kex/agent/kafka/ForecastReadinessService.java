// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.util.List;
import java.util.Set;

import com.kex.agent.mcp.McpToolCatalog;
import org.springframework.stereotype.Service;

@Service
class ForecastReadinessService {
    private static final List<String> TOOLS = List.of("kex_list_forecastable_metrics", "kex_metric_history",
            "kex_forecast_metric", "kex_list_predicted_threshold_breaches", "kex_get_forecast_quality");
    private final McpToolCatalog catalog;
    private final KafkaProperties properties;
    private final ForecastViewService forecasts;

    ForecastReadinessService(McpToolCatalog catalog, KafkaProperties properties, ForecastViewService forecasts) {
        this.catalog = catalog; this.properties = properties; this.forecasts = forecasts;
    }

    record Readiness(String connection, boolean connected, List<String> missingTools,
                     boolean catalogComplete, int seriesCount, boolean ready, String unavailable) { }

    Readiness read() {
        boolean connected = false;
        Set<String> available = Set.of();
        try {
            for (var server : catalog.servers()) {
                if (java.util.Objects.equals(properties.connection(), server.connection())) {
                    connected = server.initialized();
                    available = server.tools().stream().map(tool -> tool.name()).collect(java.util.stream.Collectors.toSet());
                }
            }
        } catch (RuntimeException ignored) { /* no transport details or secrets in diagnostics */ }
        var announced = available;
        var missing = TOOLS.stream().filter(tool -> !announced.contains(tool)).toList();
        var read = forecasts.metrics();
        boolean complete = read.unavailable() == null && !read.truncated() && read.coverage().path("complete").asBoolean(false);
        int count = complete ? read.data().size() : 0;
        return new Readiness(properties.connection(), connected, missing, complete, count,
                connected && missing.isEmpty() && complete && count > 0, read.unavailable());
    }
}
