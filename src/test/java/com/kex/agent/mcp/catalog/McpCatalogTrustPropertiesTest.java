// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpCatalogTrustPropertiesTest {

    @Test
    void valide_ses_seuils() {
        var properties = new McpCatalogTrustProperties(Duration.ofDays(180), 2, 5, List.of("ROOT"), 40, 1000,
                Set.of("https://github.com/brave/"));

        assertThat(properties.maintenanceWindow()).isEqualTo(Duration.ofDays(180));
        assertThat(properties.trustedPublisherRepositories()).containsExactly("https://github.com/brave/");

        assertThatThrownBy(() -> new McpCatalogTrustProperties(Duration.ZERO, 2, 5, List.of(), 40, 1000, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpCatalogTrustProperties(Duration.ofDays(1), -1, 5, List.of(), 40, 1000, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpCatalogTrustProperties(Duration.ofDays(1), 2, -1, List.of(), 40, 1000, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpCatalogTrustProperties(Duration.ofDays(1), 2, 5, List.of(), -1, 1000, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpCatalogTrustProperties(Duration.ofDays(1), 2, 5, List.of(), 40, -1, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tolere_des_listes_absentes() {
        var properties = new McpCatalogTrustProperties(Duration.ofDays(1), 2, 5, null, 40, 1000, null);

        assertThat(properties.broadCredentialKeywords()).isEmpty();
        assertThat(properties.trustedPublisherRepositories()).isEmpty();
    }
}
