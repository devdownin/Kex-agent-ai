// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Exact operator choice, never inferred from names or LLM hints. */
public record ForecastAssociation(@NotBlank @Size(max = 256) String seriesId,
                                  @NotBlank @Size(max = 256) String environment) { }
