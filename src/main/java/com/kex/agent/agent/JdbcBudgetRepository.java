// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Consistent lock order plus one transaction prevents overspend across replicas. */
public final class JdbcBudgetRepository implements BudgetRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public JdbcBudgetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(new JdbcTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
    }
    @Override public Reservation reserve(String id, List<Limit> limits, long tokens, long cost) {
        List<Limit> ordered = limits.stream().sorted(java.util.Comparator.comparing(Limit::scope)).toList();
        // Outside the transaction: a duplicate must not abort a PostgreSQL transaction.
        for (Limit limit : ordered) {
            try { jdbc.update("INSERT INTO kex_budget_counter (scope, tokens, cost_micros) VALUES (?, 0, 0)", limit.scope()); }
            catch (DuplicateKeyException ignored) { }
        }
        return transactions.execute(status -> {
            for (Limit limit : ordered) {
                Amount old = lock(limit.scope());
                long nextTokens = Math.addExact(old.tokens(), tokens), nextCost = Math.addExact(old.costMicros(), cost);
                if (limit.tokens() > 0 && nextTokens > limit.tokens() || limit.costMicros() > 0 && nextCost > limit.costMicros()) {
                    throw new BudgetExceededException();
                }
                jdbc.update("UPDATE kex_budget_counter SET tokens = ?, cost_micros = ? WHERE scope = ?", nextTokens, nextCost, limit.scope());
            }
            Reservation reservation = new Reservation(id, ordered.stream().map(Limit::scope).toList(), tokens, cost);
            jdbc.update("INSERT INTO kex_budget_reservation (id, scopes, tokens, cost_micros) VALUES (?, ?, ?, ?)",
                    id, String.join(",", reservation.scopes()), tokens, cost);
            return reservation;
        });
    }
    @Override public void settle(Reservation reservation, long tokens, long cost) {
        transactions.executeWithoutResult(status -> {
            List<Reservation> pending = jdbc.query("SELECT * FROM kex_budget_reservation WHERE id = ? FOR UPDATE",
                    (rs, n) -> new Reservation(rs.getString("id"), List.of(rs.getString("scopes").split(",")),
                            rs.getLong("tokens"), rs.getLong("cost_micros")), reservation.id());
            if (pending.isEmpty()) return;
            Reservation stored = pending.getFirst();
            for (String scope : stored.scopes().stream().sorted().toList()) {
                Amount old = lock(scope);
                jdbc.update("UPDATE kex_budget_counter SET tokens = ?, cost_micros = ? WHERE scope = ?",
                        Math.addExact(old.tokens(), tokens - stored.tokens()), Math.addExact(old.costMicros(), cost - stored.costMicros()), scope);
            }
            jdbc.update("DELETE FROM kex_budget_reservation WHERE id = ?", stored.id());
        });
    }
    private Amount lock(String scope) {
        return jdbc.queryForObject("SELECT tokens, cost_micros FROM kex_budget_counter WHERE scope = ? FOR UPDATE",
                (rs, n) -> new Amount(rs.getLong(1), rs.getLong(2)), scope);
    }
    @Override public Amount used(String scope) {
        return jdbc.query("SELECT tokens, cost_micros FROM kex_budget_counter WHERE scope = ?",
                (rs, n) -> new Amount(rs.getLong(1), rs.getLong(2)), scope).stream().findFirst().orElse(new Amount(0, 0));
    }
}
