// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/forecasts")
class ForecastAssociationController {
    private final ForecastAssociationService associations;
    private final ForecastReadinessService readiness;

    ForecastAssociationController(ForecastAssociationService associations, ForecastReadinessService readiness) {
        this.associations = associations; this.readiness = readiness;
    }
    record Update(@NotNull @Size(max = 4) List<@Valid ForecastAssociation> associations) { }

    @GetMapping("/readiness")
    ForecastReadinessService.Readiness readiness() { return readiness.read(); }

    @GetMapping("/processes/{id}/associations")
    ForecastAssociationService.Associations associations(@PathVariable String id) { return associations.read(id); }

    @PutMapping("/processes/{id}/associations")
    ForecastAssociationService.Associations update(@PathVariable String id, @Valid @RequestBody Update update) {
        return associations.replace(id, update.associations());
    }

    @GetMapping("/processes/{id}")
    ForecastAssociationService.Summary summary(@PathVariable String id) { return associations.summary(id); }
}
