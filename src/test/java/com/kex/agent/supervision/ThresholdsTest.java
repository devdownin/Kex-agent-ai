// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThresholdsTest {

    private static final Thresholds GLOBAL =
            new Thresholds(1000, 2.0, Duration.ofMinutes(5), 50, Duration.ofMinutes(15));

    @Test
    void sans_recouvrement_les_seuils_globaux_restent_inchanges() {
        assertThat(GLOBAL.withOverrides(null)).isEqualTo(GLOBAL);
    }

    @Test
    void un_champ_omis_herite_du_seuil_global() {
        ThresholdOverrides override = new ThresholdOverrides(5000L, null, null, null, null);

        Thresholds effective = GLOBAL.withOverrides(override);

        assertThat(effective.consumerLag()).isEqualTo(5000L);
        assertThat(effective.errorRatePercent()).isEqualTo(GLOBAL.errorRatePercent());
        assertThat(effective.processingTime()).isEqualTo(GLOBAL.processingTime());
        assertThat(effective.blockedMessages()).isEqualTo(GLOBAL.blockedMessages());
        assertThat(effective.observationWindow()).isEqualTo(GLOBAL.observationWindow());
    }

    @Test
    void chaque_champ_declare_remplace_le_sien_seul() {
        ThresholdOverrides override = new ThresholdOverrides(5000L, 10.0, Duration.ofMinutes(1), 5L,
                Duration.ofMinutes(30));

        Thresholds effective = GLOBAL.withOverrides(override);

        assertThat(effective).isEqualTo(new Thresholds(5000L, 10.0, Duration.ofMinutes(1), 5,
                Duration.ofMinutes(30)));
    }
}
