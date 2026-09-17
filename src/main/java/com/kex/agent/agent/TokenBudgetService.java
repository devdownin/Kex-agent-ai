// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.stereotype.Service;

/**
 * Ce que l'agent a dépensé aujourd'hui, et s'il a encore le droit de dépenser. Sans ceci, un cycle
 * de supervision qui part seul (voir {@code SupervisionScheduler}) n'a aucune limite : le seul
 * frein à sa dépense serait un humain qui remarque la facture.
 *
 * <p>{@code synchronized} plutôt que des types atomiques : un échange coûte au moins un aller-retour
 * réseau vers le fournisseur, l'appel à {@link #record} ne concourt jamais pour la performance.
 */
@Service
public class TokenBudgetService {

    private final long dailyLimit;
    private final Clock clock;

    private LocalDate day;
    private long consumed;

    TokenBudgetService(TokenBudgetProperties properties, Clock clock) {
        this.dailyLimit = properties.dailyLimit();
        this.clock = clock;
        this.day = LocalDate.now(clock);
    }

    /** {@code usage} est {@code null} quand le fournisseur n'a rien compté : rien à ajouter alors. */
    public synchronized void record(AgentUsage usage) {
        rolloverIfNeeded();
        if (usage != null) {
            consumed += usage.total();
        }
    }

    /** {@code false} sans plafond configuré : une borne à zéro ne s'atteint jamais. */
    public synchronized boolean exceeded() {
        rolloverIfNeeded();
        return dailyLimit > 0 && consumed >= dailyLimit;
    }

    public synchronized long consumedToday() {
        rolloverIfNeeded();
        return consumed;
    }

    public long dailyLimit() {
        return dailyLimit;
    }

    private void rolloverIfNeeded() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(day)) {
            day = today;
            consumed = 0;
        }
    }
}
