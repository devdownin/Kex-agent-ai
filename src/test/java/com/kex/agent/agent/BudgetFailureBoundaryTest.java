// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetFailureBoundaryTest {
    @Test void rejected_file_reservations_leave_every_counter_unchanged() {
        var repository = new FileBudgetRepository(new ObjectMapper(), null);
        var limits = List.of(new BudgetRepository.Limit("global", 100, 100), new BudgetRepository.Limit("task", 50, 20));
        var reserved = repository.reserve("first", limits, 30, 10);
        assertThatThrownBy(() -> repository.reserve("tokens", limits, 21, 0)).isInstanceOf(BudgetExceededException.class);
        assertThatThrownBy(() -> repository.reserve("cost", limits, 0, 11)).isInstanceOf(BudgetExceededException.class);
        assertThatThrownBy(() -> repository.reserve("first", limits, 1, 1)).isInstanceOf(IllegalStateException.class);
        assertThat(repository.used("global")).isEqualTo(new BudgetRepository.Amount(30, 10));
        assertThat(repository.used("task")).isEqualTo(new BudgetRepository.Amount(30, 10));
        repository.settle(reserved, 10, 5);
        repository.settle(reserved, 0, 0);
        assertThat(repository.used("global")).isEqualTo(new BudgetRepository.Amount(10, 5));
        assertThat(repository.used("absent")).isEqualTo(new BudgetRepository.Amount(0, 0));
    }
    @Test void unreadable_or_unwritable_state_fails_closed(@TempDir Path directory) throws Exception {
        Path corrupted = directory.resolve("bad.json"); Files.writeString(corrupted, "invalid json");
        assertThatThrownBy(() -> new FileBudgetRepository(new ObjectMapper(), corrupted)).isInstanceOf(IllegalStateException.class);
        Path blocker = directory.resolve("file"); Files.writeString(blocker, "not a directory");
        var repository = new FileBudgetRepository(new ObjectMapper(), blocker.resolve("budgets.json"));
        assertThatThrownBy(() -> repository.reserve("id", List.of(new BudgetRepository.Limit("scope", 100, 100)), 10, 10))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("appel refusé");
        assertThat(repository.used("scope")).isEqualTo(new BudgetRepository.Amount(0, 0));
    }
    @Test void incomplete_or_negative_usage_cannot_refund_a_reservation() {
        var service = new TokenBudgetService(new TokenBudgetProperties(1000), Clock.systemUTC());
        var reservation = service.reserve("tenant", "task", 100, 0);
        for (var usage : List.of(new AgentUsage(null, 5), new AgentUsage(5, null), new AgentUsage(-1, 5), new AgentUsage(5, -1))) {
            service.settle(reservation, usage);
        }
        assertThat(service.usage("tenant", null).tokens()).isEqualTo(100);
        assertThat(service.usage("tenant", "task").tokens()).isEqualTo(100);
        assertThat(service.exceeded()).isFalse();
    }
    @Test void invalid_identity_or_amount_is_rejected_before_reservation() {
        var service = new TokenBudgetService(new TokenBudgetProperties(1000), Clock.systemUTC());
        assertThatThrownBy(() -> service.reserve(null, "task", 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reserve(" ", "task", 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reserve("tenant", null, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reserve("tenant", " ", 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reserve("tenant", "task", -1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reserve("tenant", "task", 1, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.consumedToday()).isZero();
    }
    @Test void tool_tariffs_and_interruption_are_accounted_before_effects() {
        var properties = new TokenBudgetProperties(0, 0, 0, 20, 20, 20, 100, 1, 2, 1, Map.of("paid", 10L), "");
        var service = new TokenBudgetService(properties, Clock.systemUTC());
        var context = Map.<String, Object>of(TokenBudgetService.CONTEXT_KEY, service, "kex.owner", "tenant", TokenBudgetService.TASK_KEY, "task");
        TokenBudgetService.chargeTool(null, "paid");
        TokenBudgetService.chargeTool(Map.of(), "paid");
        TokenBudgetService.chargeTool(context, "paid");
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> TokenBudgetService.chargeTool(context, "paid")).isInstanceOf(java.util.concurrent.CancellationException.class);
        } finally { Thread.interrupted(); }
        assertThat(service.exceeded()).isTrue();
        assertThat(service.usage("tenant", "task").costMicros()).isEqualTo(20);
        assertThatThrownBy(() -> service.reserveTool("tenant", "task", "paid")).isInstanceOf(BudgetExceededException.class);
        assertThatThrownBy(() -> service.cost(Long.MAX_VALUE, 1)).isInstanceOf(ArithmeticException.class);
    }
}
