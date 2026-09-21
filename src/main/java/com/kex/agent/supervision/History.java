// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

/**
 * Historique borné, du plus récent au plus ancien. En mémoire du processus, donc mono-instance :
 * derrière un load balancer, chaque réplique tient le sien.
 *
 * <p>Ce qui reste ici est ce dont la divergence entre répliques se voit et ne coûte qu'un
 * rafraîchissement : cycles, anomalies brutes, relevés de processus. Ce dont la divergence produit
 * une action fausse en est sorti — voir {@link SupervisionStateRepository}.
 */
final class History<T> {

    private final Deque<T> entries = new ArrayDeque<>();
    private final int capacity;

    History(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** @return l'entrée évincée par cet ajout, {@code null} si la capacité n'était pas atteinte */
    synchronized T add(T entry) {
        entries.addFirst(entry);
        return entries.size() > capacity ? entries.removeLast() : null;
    }

    synchronized void replace(Function<T, T> mapper) {
        List<T> replaced = entries.stream().map(mapper).toList();
        entries.clear();
        entries.addAll(replaced);
    }

    synchronized List<T> list() {
        return List.copyOf(entries);
    }

    synchronized T first() {
        return entries.peekFirst();
    }
}
