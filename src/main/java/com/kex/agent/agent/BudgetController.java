// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.security.Principal;
import java.util.Map;

import com.kex.agent.config.ActorIdentity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class BudgetController {
    private final TokenBudgetService budget;
    BudgetController(TokenBudgetService budget) { this.budget = budget; }
    record View(BudgetRepository.Amount tenantToday, BudgetRepository.Amount task,
            Map<String, Long> limits, String accounting) {}
    @GetMapping("/api/agent/budgets")
    View usage(@RequestParam(required = false) String taskId, Principal actor) {
        String owner = ActorIdentity.tenantOf(actor);
        var limits = budget.limits();
        return new View(budget.usage(owner, null), taskId == null ? null : budget.usage(owner, taskId),
                Map.of("tenantDailyTokens", limits.tenantDailyLimit(), "taskTokens", limits.taskLimit(),
                        "tenantDailyCostMicros", limits.tenantDailyCostMicros(), "taskCostMicros", limits.taskCostMicros()),
                "Usage mesuré ou réservations conservatrices ; tarifs configurés en millionièmes de devise");
    }
}
