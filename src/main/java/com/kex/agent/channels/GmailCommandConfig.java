// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import com.kex.agent.agent.AgentService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "kex.agent.gmail", name = "enabled", havingValue = "true")
@EnableScheduling
class GmailCommandConfig {
    @Bean
    GmailCommandReceiver gmailCommandReceiver(GmailCommandProperties properties, AgentService agent,
                                               JavaMailSender sender) {
        return new GmailCommandReceiver(properties, agent, sender);
    }
}
