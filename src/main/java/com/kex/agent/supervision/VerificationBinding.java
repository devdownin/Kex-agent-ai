// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Administrator-defined postcondition; no model may provide or change this contract.
 * JSON pointers address the verifier's structuredContent. Only scalar equality is supported.
 * The timestamp must be an ISO-8601 instant observed after the action started; maxAge also
 * bounds its age. If measuredPointer is set, its value must be the boolean true.
 * The MCP tool must separately be authorized as read-only by the tool invocation policy.
 */
public record VerificationBinding(String connection, String tool,
                                  @DefaultValue Map<String, Object> arguments,
                                  String resultPointer, Object expectedValue,
                                  String measuredPointer, String observedAtPointer,
                                  @DefaultValue("5m") Duration maxAge) {

    public VerificationBinding {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        maxAge = maxAge == null ? Duration.ofMinutes(5) : maxAge;
    }
}
