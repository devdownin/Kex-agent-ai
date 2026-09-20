// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.isolation;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StdioIsolationPropertiesTest {

    @Test
    void validates_stdio_isolation_properties() {
        StdioIsolationProperties defaults = StdioIsolationProperties.defaults();
        assertThat(defaults.enabled()).isFalse();
        assertThat(defaults.executable()).isEqualTo("/usr/bin/docker");

        // Invalid executable
        assertThatThrownBy(() -> new StdioIsolationProperties(true, "relative/docker", Map.of(), 256, 1.0, 64))
                .isInstanceOf(IllegalArgumentException.class);

        // Invalid memory or cpus
        assertThatThrownBy(() -> new StdioIsolationProperties(true, "/usr/bin/docker", Map.of(), 5, 1.0, 64))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StdioIsolationProperties(true, "/usr/bin/docker", Map.of(), 256, -1.0, 64))
                .isInstanceOf(IllegalArgumentException.class);

        // Invalid image
        assertThatThrownBy(() -> new StdioIsolationProperties(true, "/usr/bin/docker", Map.of("cmd", "invalid image!"), 256, 1.0, 64))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
