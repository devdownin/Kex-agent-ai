// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TokenBudgetService {
    public static final String CONTEXT_KEY = "kex.budget";
    public static final String TASK_KEY = "kex.budget-task";
    private final TokenBudgetProperties properties;
    private final Clock clock;
    private final BudgetRepository repository;
    @Autowired
    public TokenBudgetService(TokenBudgetProperties properties, Clock clock, BudgetRepository repository) {
        this.properties = properties; this.clock = clock; this.repository = repository;
    }
    TokenBudgetService(TokenBudgetProperties properties, Clock clock) {
        this(properties, clock, new FileBudgetRepository(new com.fasterxml.jackson.databind.ObjectMapper(), null));
    }
    public BudgetRepository.Reservation reserve(String owner, String task, long tokens, long cost) {
        if (owner == null || owner.isBlank() || task == null || task.isBlank() || tokens < 0 || cost < 0) {
            throw new IllegalArgumentException("Identité et montants de réservation invalides");
        }
        String day = LocalDate.now(clock).toString();
        return repository.reserve(UUID.randomUUID().toString(), List.of(
                new BudgetRepository.Limit("global:" + day, properties.dailyLimit(), properties.dailyCostMicros()),
                new BudgetRepository.Limit("tenant:" + hash(owner) + ":" + day, properties.tenantDailyLimit(), properties.tenantDailyCostMicros()),
                new BudgetRepository.Limit("task:" + hash(owner + "\u0000" + task), properties.taskLimit(), properties.taskCostMicros())), tokens, cost);
    }
    public void settle(BudgetRepository.Reservation reservation, AgentUsage usage) {
        if (usage != null && usage.inputTokens() != null && usage.outputTokens() != null
                && usage.inputTokens() >= 0 && usage.outputTokens() >= 0) {
            repository.settle(reservation, usage.total(), cost(usage.inputTokens(), usage.outputTokens()));
        }
    }
    public long cost(long input, long output) {
        return Math.addExact(Math.multiplyExact(input, properties.inputTokenCostMicros()),
                Math.multiplyExact(output, properties.outputTokenCostMicros()));
    }
    public TokenBudgetProperties limits() { return properties; }
    public int outputReservation() { return properties.outputReservation(); }
    public BudgetRepository.Reservation reserveTool(String owner, String task, String tool) {
        BudgetRepository.Reservation reservation = reserve(owner, task, 0, properties.toolCostMicros().getOrDefault(tool, properties.defaultToolCostMicros()));
        repository.settle(reservation, reservation.tokens(), reservation.costMicros());
        return reservation;
    }
    public static void chargeTool(Map<String, Object> context, String tool) {
        if (context != null && context.get(CONTEXT_KEY) instanceof TokenBudgetService service) {
            service.reserveTool(context.get("kex.owner").toString(), context.get(TASK_KEY).toString(), tool);
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Appel interrompu avant exécution");
        }
    }
    /** Compatibility for external usage producers; AgentService no longer double-counts the last turn. */
    public void record(AgentUsage usage) {
        if (usage == null) return;
        var reservation = repository.reserve(UUID.randomUUID().toString(), List.of(new BudgetRepository.Limit(globalScope(), 0, 0)),
                usage.total(), cost(usage.inputTokens() == null ? 0 : usage.inputTokens(), usage.outputTokens() == null ? 0 : usage.outputTokens()));
        repository.settle(reservation, reservation.tokens(), reservation.costMicros());
    }
    public boolean exceeded() {
        BudgetRepository.Amount amount = repository.used(globalScope());
        return properties.dailyLimit() > 0 && amount.tokens() >= properties.dailyLimit()
                || properties.dailyCostMicros() > 0 && amount.costMicros() >= properties.dailyCostMicros();
    }
    public long consumedToday() { return repository.used(globalScope()).tokens(); }
    public long dailyLimit() { return properties.dailyLimit(); }
    public BudgetRepository.Amount usage(String owner, String task) {
        return repository.used(task == null ? "tenant:" + hash(owner) + ":" + LocalDate.now(clock)
                : "task:" + hash(owner + "\u0000" + task));
    }
    private String globalScope() { return "global:" + LocalDate.now(clock); }
    private static String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
