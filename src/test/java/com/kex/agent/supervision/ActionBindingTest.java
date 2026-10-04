// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class ActionBindingTest {

    @Test
    void binds_existing_configuration_without_a_verifier() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "action.connection", "ops", "action.tool", "restart", "action.arguments.group", "orders")));

        ActionBinding binding = binder.bind("action", ActionBinding.class).get();

        assertThat(binding.arguments()).containsEntry("group", "orders");
        assertThat(binding.verification()).isNull();
    }

    @Test
    void binds_a_typed_verification_contract_and_default_age() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "action.connection", "ops", "action.tool", "restart",
                "action.verification.connection", "ops", "action.verification.tool", "health",
                "action.verification.result-pointer", "/healthy", "action.verification.expected-value", true,
                "action.verification.observed-at-pointer", "/observedAt"));

        ActionBinding binding = new Binder(source).bind("action", ActionBinding.class).get();

        assertThat(binding.verification().expectedValue()).isEqualTo(true);
        assertThat(binding.verification().maxAge()).isEqualTo(Duration.ofMinutes(5));
    }
}
