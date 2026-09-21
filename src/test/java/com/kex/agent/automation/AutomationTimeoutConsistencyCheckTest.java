// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.automation;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.kex.agent.config.AgentProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationTimeoutConsistencyCheckTest {

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(AutomationTimeoutConsistencyCheck.class);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
    }

    @Test
    void avertit_quand_le_bail_est_plus_court_que_le_plafond_de_l_echange() {
        new AutomationTimeoutConsistencyCheck(automation(true, Duration.ofSeconds(30)), agent(Duration.ofMinutes(2)))
                .check();

        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("automation.timeout").contains("request-timeout");
        });
    }

    @Test
    void se_tait_quand_le_bail_couvre_deja_le_plafond_de_l_echange() {
        new AutomationTimeoutConsistencyCheck(automation(true, Duration.ofMinutes(5)), agent(Duration.ofMinutes(2)))
                .check();

        assertThat(logs.list).isEmpty();
    }

    @Test
    void se_tait_quand_les_automatisations_sont_eteintes() {
        new AutomationTimeoutConsistencyCheck(automation(false, Duration.ofSeconds(30)), agent(Duration.ofMinutes(2)))
                .check();

        assertThat(logs.list).isEmpty();
    }

    private static AutomationProperties automation(boolean enabled, Duration timeout) {
        return new AutomationProperties(enabled, timeout, 5, 16000, Set.of("kex_list_topics"));
    }

    private static AgentProperties agent(Duration requestTimeout) {
        return new AgentProperties("prompt", 40, 4000, false, "", Map.of(), Map.of(), Map.of(), requestTimeout);
    }
}
