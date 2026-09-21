// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class SupervisionScheduleConsistencyCheckTest {

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(SupervisionScheduleConsistencyCheck.class);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
    }

    @Test
    void avertit_quand_le_departement_automatique_est_active_sans_le_profil_partage() {
        MockEnvironment environment = new MockEnvironment();

        new SupervisionScheduleConsistencyCheck(properties(true), environment).check();

        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("shared-memory");
        });
    }

    @Test
    void se_tait_quand_le_profil_partage_est_actif() {
        MockEnvironment environment = new MockEnvironment();
        environment.addActiveProfile("shared-memory");

        new SupervisionScheduleConsistencyCheck(properties(true), environment).check();

        assertThat(logs.list).isEmpty();
    }

    @Test
    void se_tait_quand_le_depart_automatique_est_eteint() {
        new SupervisionScheduleConsistencyCheck(properties(false), new MockEnvironment()).check();

        assertThat(logs.list).isEmpty();
    }

    private static SupervisionProperties properties(boolean scheduleEnabled) {
        return new SupervisionProperties(true, List.of(), ExecutionMode.SUPERVISED, 0.85,
                new Thresholds(1000, 2.0, Duration.ofMinutes(5), 50, Duration.ofMinutes(15)),
                Map.of(), Map.of(), Map.of(), 200, Duration.ofMinutes(30), Duration.ofMinutes(15),
                new SupervisionProperties.Schedule(scheduleEnabled, Duration.ofMinutes(5), Duration.ofMinutes(10),
                        new SupervisionProperties.Adaptive(false, Duration.ofMinutes(5), Duration.ofMinutes(30))),
                new SupervisionProperties.AutoAdjust(false, 5, 0.5, 0.05),
                new SupervisionProperties.Learning(false, 3, Duration.ofDays(30)),
                new SupervisionProperties.Correlation(true, 3), false);
    }
}
