// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.execution;

import java.time.Duration;
import java.util.Map;

import com.kex.agent.supervision.Capability;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("kex.agent.tasks")
@Validated
public record TaskProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("${user.home}/.kex-agent-ai/tasks") String storePath,
        @DefaultValue("16") @Min(1) @Max(32) int maxSteps,
        @DefaultValue("4") @Min(1) @Max(16) int maxConcurrent,
        @DefaultValue("10m") Duration runTimeout,
        @DefaultValue("60s") Duration callTimeout,
        @DefaultValue("8000") @Min(512) @Max(64000) int maxResultCharacters,
        @DefaultValue Map<String, Binding> bindings) {
    public TaskProperties {
        storePath = storePath.replace("${user.home}", System.getProperty("user.home"));
        bindings = bindings == null ? Map.of() : Map.copyOf(bindings);
        if (runTimeout == null || callTimeout == null || callTimeout.isNegative()
                || callTimeout.isZero() || callTimeout.compareTo(runTimeout) >= 0) {
            throw new IllegalArgumentException("Le délai d'outil doit être positif et inférieur au délai de tâche");
        }
    }
    /** Only trusted deployment configuration classifies a tool as read-only. */
    public record Binding(String connection, String tool, @DefaultValue("false") boolean readOnly,
            Capability capability, String idempotencyArgument, Verifier verifier) {}
    public record Verifier(String binding, Map<String, Object> arguments, String pointer,
            Object expected, @DefaultValue("/observedAt") String observedAtPointer, String measuredPointer) {}
}
