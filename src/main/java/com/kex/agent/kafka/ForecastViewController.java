// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** La sécurité existante réserve ces lectures aux opérateurs et administrateurs. */
@RestController
@RequestMapping("/api/agent/forecasts")
class ForecastViewController {
    private final ForecastViewService forecasts;

    ForecastViewController(ForecastViewService forecasts) {
        this.forecasts = forecasts;
    }

    @GetMapping("/metrics")
    ForecastViewService.Read metrics() {
        return forecasts.metrics();
    }

    @GetMapping("/breaches")
    ForecastViewService.Read breaches() {
        return forecasts.breaches();
    }

    @GetMapping("/series/{seriesId}")
    ForecastViewService.Detail detail(@PathVariable String seriesId) {
        return forecasts.detail(seriesId);
    }
}
