// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.automation;

import java.time.Clock;
import java.util.Set;

import com.kex.agent.agent.AgentService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("shared-memory")
@ConditionalOnProperty(prefix = "kex.agent.automation", name = "enabled", havingValue = "true")
@EnableScheduling
class AutomationConfig {
    @Bean JdbcAutomationRepository automationRepository(JdbcTemplate jdbc) { return new JdbcAutomationRepository(jdbc); }
    @Bean AutomationService automationService(JdbcAutomationRepository repository, AutomationProperties properties, Clock clock) {
        return new AutomationService(repository, properties, clock);
    }
    @Bean ScheduledTaskExecutor scheduledTaskExecutor(AgentService agent, AutomationProperties properties) {
        Set<String> readOnly = properties.readOnlyTools();
        if (readOnly.isEmpty()) throw new IllegalArgumentException("automation.read-only-tools doit être explicitement configuré");
        return (owner, conversation, prompt) -> agent.askReadOnly(owner, conversation, prompt, readOnly).content();
    }
    @Bean AutomationTicker automationTicker(AutomationService service, ScheduledTaskExecutor executor) {
        return new AutomationTicker(service, executor);
    }
    static final class AutomationTicker {
        private final AutomationService service; private final ScheduledTaskExecutor executor;
        AutomationTicker(AutomationService service, ScheduledTaskExecutor executor) { this.service = service; this.executor = executor; }
        @Scheduled(fixedDelayString = "${kex.agent.automation.poll-interval:15s}")
        void tick() { service.tick(executor); }
    }
}
