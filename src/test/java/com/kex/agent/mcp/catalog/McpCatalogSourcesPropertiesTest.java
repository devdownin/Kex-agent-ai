// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpCatalogSourcesPropertiesTest {

    @Test
    void valide_les_bornes_de_pagination_d_une_source() {
        var source = new McpCatalogSourcesProperties.Source(true, "https://hub.docker.com", 2, 100,
                Duration.ofSeconds(5));
        assertThat(source.enabled()).isTrue();

        assertThatThrownBy(() -> new McpCatalogSourcesProperties.Source(true, "u", 0, 100, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpCatalogSourcesProperties.Source(true, "u", 21, 100, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpCatalogSourcesProperties.Source(true, "u", 2, 0, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpCatalogSourcesProperties.Source(true, "u", 2, 101, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpCatalogSourcesProperties.Source(true, "u", 2, 100, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
