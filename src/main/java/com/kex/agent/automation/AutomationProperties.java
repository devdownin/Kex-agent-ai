// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.automation;

import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("kex.agent.automation")
public record AutomationProperties(@DefaultValue("false") boolean enabled,
                                   @DefaultValue("2m") Duration timeout,
                                   @DefaultValue("5") int batchSize,
                                   @DefaultValue("16000") int maxResultChars,
                                   @DefaultValue Set<String> readOnlyTools) {
    public AutomationProperties {
        readOnlyTools = readOnlyTools == null ? Set.of() : Set.copyOf(readOnlyTools);
        if (timeout == null || timeout.compareTo(Duration.ofSeconds(1)) < 0
                || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("automation.timeout doit être compris entre 1s et 10m");
        }
        if (batchSize < 1 || batchSize > 20 || maxResultChars < 256 || maxResultChars > 64000) {
            throw new IllegalArgumentException("automation.batch-size doit être 1..20 et max-result-chars 256..64000");
        }
    }
}
