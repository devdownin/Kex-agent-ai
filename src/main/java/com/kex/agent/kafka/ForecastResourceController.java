// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/forecasts")
class ForecastResourceController {
    private final ForecastResourceService resources;

    ForecastResourceController(ForecastResourceService resources) { this.resources = resources; }

    @GetMapping("/series/{seriesId}/resources")
    ForecastResourceService.Resources resources(@PathVariable String seriesId) {
        return resources.resources(seriesId);
    }
}
