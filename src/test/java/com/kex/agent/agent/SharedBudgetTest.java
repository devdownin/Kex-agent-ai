// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.testsupport.FlywayTestSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SharedBudgetTest {
    private static TokenBudgetProperties limits(long daily, long tenant, long task, long cost) {
        return new TokenBudgetProperties(daily, tenant, task, cost, cost, cost, 100, 1, 2, 10, Map.of(), "");
    }
    private static JdbcTemplate database() {
        var data = new SimpleDriverDataSource(); data.setDriverClass(org.h2.Driver.class);
        data.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        FlywayTestSchema.migrate(data); return new JdbcTemplate(data);
    }
    @Test void concurrent_replicas_cannot_overspend_any_scope() throws Exception {
        JdbcTemplate jdbc = database();
        var first = new TokenBudgetService(limits(100, 100, 100, 100), Clock.systemUTC(), new JdbcBudgetRepository(jdbc));
        var second = new TokenBudgetService(limits(100, 100, 100, 100), Clock.systemUTC(), new JdbcBudgetRepository(jdbc));
        AtomicInteger accepted = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures = java.util.stream.IntStream.range(0, 12).mapToObj(i -> pool.submit(() -> {
                try { (i % 2 == 0 ? first : second).reserve("tenant", "task", 25, 25); accepted.incrementAndGet(); }
                catch (BudgetExceededException expected) { }
            })).toList();
            for (var future : futures) future.get();
        }
        assertThat(accepted).hasValue(4);
        assertThat(second.usage("tenant", "task")).isEqualTo(new BudgetRepository.Amount(100, 100));
        assertThat(first.consumedToday()).isEqualTo(100);
    }
    @Test void denied_reservation_rolls_back_all_scopes_and_settlement_is_idempotent() {
        JdbcTemplate jdbc = database(); var repository = new JdbcBudgetRepository(jdbc);
        var service = new TokenBudgetService(limits(1000, 100, 60, 1000), Clock.systemUTC(), repository);
        var reservation = service.reserve("one", "task", 50, 100);
        assertThatThrownBy(() -> service.reserve("one", "task", 20, 0)).isInstanceOf(BudgetExceededException.class);
        assertThat(service.consumedToday()).isEqualTo(50);
        service.settle(reservation, new AgentUsage(10, 5));
        service.settle(reservation, new AgentUsage(10, 5));
        assertThat(service.usage("one", "task")).isEqualTo(new BudgetRepository.Amount(15, 20));
        service.reserve("two", "task", 50, 10);
        assertThat(service.usage("two", "task").tokens()).isEqualTo(50);
    }
    @Test void reservations_survive_restart_and_missing_usage_is_not_free(@TempDir Path directory) {
        var path = directory.resolve("budgets.json");
        var json = new ObjectMapper();
        var first = new TokenBudgetService(limits(200, 200, 200, 200), Clock.systemUTC(), new FileBudgetRepository(json, path));
        var reservation = first.reserve("one", "task", 100, 100);
        first.settle(reservation, null);
        var next = new TokenBudgetService(limits(200, 200, 200, 200), Clock.systemUTC(), new FileBudgetRepository(json, path));
        assertThat(next.usage("one", "task")).isEqualTo(new BudgetRepository.Amount(100, 100));
        assertThatThrownBy(() -> next.reserve("one", "task", 101, 0)).isInstanceOf(BudgetExceededException.class);
    }
    @Test void tools_spend_cost_before_execution() {
        var service = new TokenBudgetService(limits(0, 0, 0, 20), Clock.systemUTC());
        service.reserveTool("tenant", "task", "echo"); service.reserveTool("tenant", "task", "echo");
        assertThatThrownBy(() -> service.reserveTool("tenant", "task", "echo")).isInstanceOf(BudgetExceededException.class);
        assertThat(service.usage("tenant", "task").costMicros()).isEqualTo(20);
    }
}
