// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Single-instance durable fallback. Multi-replica deployments use JdbcBudgetRepository. */
public final class FileBudgetRepository implements BudgetRepository {
    public record State(Map<String, Amount> counters, Map<String, Reservation> pending) {}
    private final ObjectMapper mapper;
    private final Path path;
    private State state;
    public FileBudgetRepository(ObjectMapper mapper, Path path) {
        this.mapper = mapper; this.path = path;
        try {
            state = path != null && Files.exists(path) ? mapper.readValue(path.toFile(), State.class)
                    : new State(Map.of(), Map.of());
        } catch (Exception ex) { throw new IllegalStateException("Budgets persistés illisibles", ex); }
    }
    @Override public synchronized Reservation reserve(String id, List<Limit> limits, long tokens, long cost) {
        Map<String, Amount> counters = new HashMap<>(state.counters());
        for (Limit limit : limits) {
            Amount old = counters.getOrDefault(limit.scope(), new Amount(0, 0));
            long nextTokens = Math.addExact(old.tokens(), tokens), nextCost = Math.addExact(old.costMicros(), cost);
            if (limit.tokens() > 0 && nextTokens > limit.tokens() || limit.costMicros() > 0 && nextCost > limit.costMicros()) {
                throw new BudgetExceededException();
            }
            counters.put(limit.scope(), new Amount(nextTokens, nextCost));
        }
        Reservation reservation = new Reservation(id, limits.stream().map(Limit::scope).toList(), tokens, cost);
        Map<String, Reservation> pending = new HashMap<>(state.pending());
        if (pending.putIfAbsent(id, reservation) != null) throw new IllegalStateException("Réservation existante");
        save(new State(counters, pending)); return reservation;
    }
    @Override public synchronized void settle(Reservation reservation, long tokens, long cost) {
        Reservation stored = state.pending().get(reservation.id());
        if (stored == null) return;
        Map<String, Amount> counters = new HashMap<>(state.counters());
        for (String scope : stored.scopes()) {
            Amount old = counters.get(scope);
            counters.put(scope, new Amount(Math.addExact(old.tokens(), tokens - stored.tokens()),
                    Math.addExact(old.costMicros(), cost - stored.costMicros())));
        }
        Map<String, Reservation> pending = new HashMap<>(state.pending()); pending.remove(stored.id());
        save(new State(counters, pending));
    }
    @Override public synchronized Amount used(String scope) {
        return state.counters().getOrDefault(scope, new Amount(0, 0));
    }
    private void save(State next) {
        if (path != null) {
            Path temp = null;
            try {
                Path absolute = path.toAbsolutePath(); Files.createDirectories(absolute.getParent());
                temp = Files.createTempFile(absolute.getParent(), "budget-", ".tmp");
                mapper.writeValue(temp.toFile(), next);
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ex) { throw new IllegalStateException("Budget non persisté ; appel refusé", ex); }
            finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (Exception ignored) {} }
        }
        state = next;
    }
}
