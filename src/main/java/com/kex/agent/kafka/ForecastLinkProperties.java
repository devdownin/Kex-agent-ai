// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Explicit operator associations: labels and LLM hints are never resource provenance. */
@ConfigurationProperties("kex.agent.forecasts")
@Validated
public record ForecastLinkProperties(@Valid @Size(max = 256) List<ProcessLink> processLinks) {
    public ForecastLinkProperties {
        processLinks = processLinks == null ? List.of() : List.copyOf(processLinks);
    }

    public record ProcessLink(@NotBlank String seriesId, @NotBlank String environment,
                              @Size(max = 50) List<@NotBlank String> processIds) {
        public ProcessLink {
            processIds = processIds == null ? List.of() : List.copyOf(processIds);
        }
    }
}
