// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Défaut mono-instance : exactement ce que {@code SupervisionService} portait dans ses champs
 * avant que l'état ne devienne une interface. Rien n'a changé pour une installation à une seule
 * réplique — c'est la majorité d'entre elles, et elles n'ont pas à payer une base pour ça.
 */
class InMemorySupervisionStateRepository implements SupervisionStateRepository {

    private final History<Decision> decisions;
    private final Map<String, Decision> decisionsById = new ConcurrentHashMap<>();
    private final Map<String, MaintenanceWindow> maintenance = new ConcurrentHashMap<>();
    private final Set<String> claimed = ConcurrentHashMap.newKeySet();
    private volatile boolean paused;

    InMemorySupervisionStateRepository(int historySize) {
        this.decisions = new History<>(historySize);
    }

    @Override
    public boolean paused() {
        return paused;
    }

    @Override
    public void paused(boolean paused) {
        this.paused = paused;
    }

    @Override
    public void putMaintenance(MaintenanceWindow window) {
        maintenance.put(window.processId(), window);
    }

    @Override
    public MaintenanceWindow removeMaintenance(String processId) {
        return maintenance.remove(processId);
    }

    @Override
    public List<MaintenanceWindow> activeMaintenance(Instant now) {
        maintenance.values().removeIf(window -> !now.isBefore(window.until()));
        return List.copyOf(maintenance.values());
    }

    @Override
    public void store(Decision decision) {
        Decision previous = decisionsById.put(decision.id(), decision);
        if (previous == null) {
            Decision evicted = decisions.add(decision);
            if (evicted != null) {
                decisionsById.remove(evicted.id());
                // Sans cette ligne, la table des réservations serait le seul état non borné ici.
                claimed.remove(evicted.id());
            }
        }
        else {
            decisions.replace(entry -> entry.id().equals(decision.id()) ? decision : entry);
        }
    }

    @Override
    public Optional<Decision> decision(String id) {
        return Optional.ofNullable(decisionsById.get(id));
    }

    @Override
    public List<Decision> decisions() {
        return decisions.list();
    }

    @Override
    public boolean claim(String id) {
        return claimed.add(id);
    }
}
