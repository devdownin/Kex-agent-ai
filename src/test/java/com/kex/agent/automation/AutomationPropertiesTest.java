// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.automation;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutomationPropertiesTest {

    @Test
    void validates_automation_properties() {
        AutomationProperties props = new AutomationProperties(true, Duration.ofMinutes(1), 10, 8000, Set.of("tool1"));

        assertThat(props.enabled()).isTrue();
        assertThat(props.timeout()).isEqualTo(Duration.ofMinutes(1));
        assertThat(props.batchSize()).isEqualTo(10);
        assertThat(props.maxResultChars()).isEqualTo(8000);
        assertThat(props.readOnlyTools()).containsExactly("tool1");

        // Invalid timeout
        assertThatThrownBy(() -> new AutomationProperties(true, Duration.ofMillis(500), 5, 1000, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutomationProperties(true, Duration.ofMinutes(20), 5, 1000, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);

        // Invalid batchSize or maxResultChars
        assertThatThrownBy(() -> new AutomationProperties(true, Duration.ofMinutes(2), 0, 1000, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutomationProperties(true, Duration.ofMinutes(2), 25, 1000, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutomationProperties(true, Duration.ofMinutes(2), 5, 100, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
