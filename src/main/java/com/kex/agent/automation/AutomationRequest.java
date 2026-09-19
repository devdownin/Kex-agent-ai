// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.automation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AutomationRequest(@NotBlank @Size(max = 160) String name,
                                @NotBlank @Size(max = 8000) String prompt,
                                @NotBlank @Size(max = 120) String cron,
                                @NotBlank @Size(max = 80) String zone,
                                boolean enabled) {
}
