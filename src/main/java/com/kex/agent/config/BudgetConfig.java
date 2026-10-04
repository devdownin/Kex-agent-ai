// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.agent.BudgetRepository;
import com.kex.agent.agent.FileBudgetRepository;
import com.kex.agent.agent.JdbcBudgetRepository;
import com.kex.agent.agent.TokenBudgetProperties;
import com.kex.agent.agent.TokenBudgetService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
class BudgetConfig {
    @Bean @Profile("!shared-memory")
    BudgetRepository fileBudgets(ObjectMapper mapper, TokenBudgetProperties properties) {
        return new FileBudgetRepository(mapper, Path.of(properties.storePath()));
    }
    @Bean @Profile("shared-memory")
    BudgetRepository sharedBudgets(JdbcTemplate jdbc) { return new JdbcBudgetRepository(jdbc); }
    @Bean static BeanPostProcessor budgetModels(ObjectProvider<TokenBudgetService> budgets) {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof ChatModel model && !(bean instanceof RoutingChatModel)
                        && !(bean instanceof BudgetedChatModel)) return new BudgetedChatModel(model, budgets::getObject);
                return bean;
            }
        };
    }
}
